package com.musicplayer.player;

import com.musicplayer.DebugLog;
import com.musicplayer.VanillaTrack;
import com.musicplayer.config.MusicPlayerConfig;
import com.musicplayer.hud.NotificationManager;
import com.musicplayer.mixin.WeighedSoundEventsAccessor;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.Sound.Type;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.client.sounds.Weighted;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

public class SoundtrackStateManager {
   private static SoundtrackStateManager instance;
   private final Minecraft client;
   private final SoundThreadExecutor soundExecutor;
   private Runnable onTrackFinished;
   private float volume = 1.0F;
   private long playbackSessionId;
   private boolean manualPlaying;
   private boolean manualPaused;
   private SoundInstance manualInstance;
   private VanillaTrack manualTrack;
   private long accumulatedPlayTime;
   private long resumeStartMs;
   private int orphanedStateTicks;
   private static final int PHASE_NONE = 0;
   private static final int PHASE_DETECTING = 1;
   private static final int PHASE_CLEANUP = 2;
   private static final int PHASE_CALLBACK = 3;
   private static final int INACTIVE_THRESHOLD = 3;
   private int completionPhase = 0;
   private int inactiveTickCount;
   private boolean callbackExecuted;
   private boolean confirmedActive;
   private static final long PLAY_GRACE_PERIOD_MS = 800L;
   private long lastPlayAttemptTime;
   private boolean pendingFailedAdvance;
   private boolean pendingPause;
   private VanillaPCMCache vanillaPCMCache;
   private static final int STORM_THRESHOLD = 3;
   private static final long STORM_WINDOW_MS = 5000L;
   private static final long DISABLE_DURATION_MS = 10000L;
   private int failedPlaybackAttempts;
   private Identifier lastFailedTrack;
   private long lastFailedTime;
   private long disablePlaybackUntil;
   private VanillaTrack savedManualTrack;
   private boolean restoreOnFocus;
   private SoundInstance autoInstance;
   private VanillaTrack autoTrack;
   private long autoStartMs;
   private boolean autoPaused;
   private long autoPausedAtMs;
   private boolean fading;
   private long fadeStartMs;
   private long fadeDurationMs;
   private float fadeStartVol;
   private float fadeTargetVol;
   private boolean ducking;
   private float duckRestoreVolume;
   private static final int MAX_VALIDATION_DEPTH = 10;

   private SoundtrackStateManager(Minecraft var1) {
      this.client = client;
      this.soundExecutor = SoundThreadExecutor.getInstance();
   }

   public static synchronized SoundtrackStateManager getInstance() {
      if (instance == null) {
         instance = new SoundtrackStateManager(Minecraft.getInstance());
      }

      return instance;
   }

   public void setOnTrackFinished(Runnable var1) {
      this.onTrackFinished = callback;
   }

   public void playTrack(VanillaTrack var1) {
      try {
         if (this.disablePlaybackUntil > monotonicTimeMs()) {
            DebugLog.info("playTrack: disabled until %d", this.disablePlaybackUntil);
            return;
         }

         if (SoundtrackRegistry.getInstance().isVariantFailed(track.resolvedSoundId())) {
            DebugLog.info("playTrack: variant %s is marked failed, advancing", track.resolvedSoundId());
            this.pendingFailedAdvance = true;
            return;
         }

         DebugLog.info("playTrack: \"%s\" by %s (event=%s variant=%s)", track.title(), track.artist(), track.soundIdentifier(), track.resolvedSoundId());
         this.stopManual();
         this.client.getMusicManager().stopPlaying();
         if (this.autoInstance != null) {
            try {
               this.client.getSoundManager().stop(this.autoInstance);
            } catch (Exception exception) {
            }
         }

         this.clearAutoMusic();
         this.playbackSessionId++;
         this.manualInstance = createTrackInstance(track);
         if (this.manualInstance == null) {
            DebugLog.info("playTrack: createTrackInstance returned null for \"%s\"", track.title());
            this.handleFailedTrack(track);
            return;
         }

         DebugLog.info("playTrack: created instance type=%s", this.manualInstance.getClass().getSimpleName());
         this.failedPlaybackAttempts = 0;
         this.lastFailedTrack = null;
         this.manualPlaying = true;
         this.manualPaused = false;
         this.accumulatedPlayTime = 0L;
         this.resumeStartMs = monotonicTimeMs();
         this.manualTrack = track;
         this.resetCompletionState();
         this.confirmedActive = false;
         this.lastPlayAttemptTime = monotonicTimeMs();
         this.client.getSoundManager().play(this.manualInstance);
         DebugLog.info("playTrack: SoundManager.play() called, session=%d", this.playbackSessionId);
         this.loadPCMCache(track);
         NotificationManager.notify("Now Playing: " + track.title(), "Minecraft Soundtrack", -11141291);
      } catch (Throwable throwable) {
         DebugLog.info("playTrack: caught %s: %s", throwable.getClass().getSimpleName(), throwable.getMessage());
         throwable.printStackTrace();
         this.manualPlaying = false;
         this.manualInstance = null;
         this.resetCompletionState();
         if (track != null) {
            this.handleFailedTrack(track);
         }
      }
   }

   private void handleFailedTrack(VanillaTrack var1) {
      Identifier identifier = track.resolvedSoundId() != null ? track.resolvedSoundId() : track.soundIdentifier();
      DebugLog.info("handleFailedTrack: variant=%s attempt=%d", identifier, this.failedPlaybackAttempts + 1);
      this.lastPlayAttemptTime = 0L;
      SoundtrackRegistry.getInstance().markVariantFailed(track.resolvedSoundId());
      long i = monotonicTimeMs();
      if (identifier.equals(this.lastFailedTrack) && i - this.lastFailedTime < 5000L) {
         this.failedPlaybackAttempts++;
      } else {
         this.failedPlaybackAttempts = 1;
      }

      this.lastFailedTrack = identifier;
      this.lastFailedTime = i;
      if (this.failedPlaybackAttempts >= 3) {
         this.disablePlaybackUntil = i + 10000L;
         DebugLog.info("handleFailedTrack: storm threshold reached, disabling until %d", this.disablePlaybackUntil);
      }

      this.clearPCMCache();
      this.manualPlaying = false;
      this.manualInstance = null;
      this.manualTrack = null;
      this.manualPaused = false;
      this.accumulatedPlayTime = 0L;
      this.resumeStartMs = 0L;
      this.resetCompletionState();
      this.pendingFailedAdvance = true;
   }

   private void loadPCMCache(VanillaTrack var1) {
      this.clearPCMCache();
      if (track.resolvedSoundId() != null) {
         String s = "sounds/" + track.resolvedSoundId().getPath() + ".ogg";
         Identifier identifier = Identifier.fromNamespaceAndPath(track.resolvedSoundId().getNamespace(), s);
         Thread thread = new Thread(() -> this.vanillaPCMCache = VanillaPCMCache.decode(identifier), "musicplayer-pcm-loader");
         thread.setDaemon(true);
         thread.start();
      }
   }

   private void clearPCMCache() {
      this.vanillaPCMCache = null;
   }

   public boolean getVisualizerBars(float[] var1, int var2) {
      VanillaPCMCache vanillapcmcache = this.vanillaPCMCache;
      if (vanillapcmcache == null) {
         return false;
      } else {
         vanillapcmcache.getBars(bars, numBars, this.getElapsedMs());
         return true;
      }
   }

   public void stopManual() {
      this.clearPCMCache();
      if (this.manualInstance != null) {
         try {
            this.client.getSoundManager().stop(this.manualInstance);
         } catch (Exception exception) {
         }

         this.manualInstance = null;
      }

      this.manualTrack = null;
      this.manualPlaying = false;
      this.manualPaused = false;
      this.accumulatedPlayTime = 0L;
      this.resumeStartMs = 0L;
      this.resetCompletionState();
   }

   public void unfocus() {
      this.restoreOnFocus = this.manualPlaying && !this.manualPaused && this.manualTrack != null;
      if (this.restoreOnFocus) {
         this.savedManualTrack = this.manualTrack;
      }

      if (this.manualInstance != null) {
         try {
            this.client.getSoundManager().stop(this.manualInstance);
         } catch (Exception exception1) {
         }

         this.manualInstance = null;
      }

      if (this.autoInstance != null) {
         try {
            this.client.getSoundManager().stop(this.autoInstance);
         } catch (Exception exception) {
         }

         this.clearAutoMusic();
      }

      this.clearPCMCache();
      this.manualTrack = null;
      this.manualPlaying = false;
      this.manualPaused = false;
      this.accumulatedPlayTime = 0L;
      this.resumeStartMs = 0L;
      this.resetCompletionState();
      this.pendingPause = false;
      this.fading = false;
   }

   public void refocus() {
      if (this.restoreOnFocus && this.savedManualTrack != null) {
         this.playTrack(this.savedManualTrack);
      }

      this.restoreOnFocus = false;
      this.savedManualTrack = null;
   }

   public void togglePause() {
      if (this.manualInstance != null && this.manualTrack != null) {
         if (this.manualPaused) {
            this.resumeManual();
         } else {
            this.pauseManual();
         }
      }
   }

   public void pauseImmediate() {
      if (this.manualPlaying && this.manualInstance != null && !this.manualPaused && !this.pendingPause) {
         long i = this.accumulatedPlayTime + (monotonicTimeMs() - this.resumeStartMs);
         this.accumulatedPlayTime = i;
         this.pendingPause = false;
         this.fading = false;
         this.soundExecutor.pause(this.manualInstance);
         this.manualPaused = true;
      }

      if (this.autoInstance != null && !this.autoPaused) {
         this.autoPausedAtMs = monotonicTimeMs() - this.autoStartMs;
         this.soundExecutor.pause(this.autoInstance);
         this.autoPaused = true;
      }
   }

   public void resumeImmediate() {
      if (this.manualInstance != null && this.manualPaused) {
         this.soundExecutor.resume(this.manualInstance, this.volume);
         this.resumeStartMs = monotonicTimeMs();
         this.manualPaused = false;
      }

      if (this.autoInstance != null && this.autoPaused) {
         this.soundExecutor.resume(this.autoInstance, this.volume);
         this.autoStartMs = monotonicTimeMs() - this.autoPausedAtMs;
         this.autoPaused = false;
      }
   }

   private void pauseManual() {
      if (!this.manualPaused && !this.pendingPause) {
         MusicPlayerConfig musicplayerconfig = MusicPlayerConfig.getInstance();
         long i = (long)musicplayerconfig.getFadeDurationMs();
         if (musicplayerconfig.isFadeOnPause() && i > 0L) {
            this.pendingPause = true;
            this.startFade(0.0F, i);
         } else {
            long j = this.accumulatedPlayTime + (monotonicTimeMs() - this.resumeStartMs);
            this.accumulatedPlayTime = j;
            this.soundExecutor.pause(this.manualInstance);
            this.manualPaused = true;
         }
      }
   }

   private void resumeManual() {
      if (this.manualPaused && this.manualInstance != null) {
         MusicPlayerConfig musicplayerconfig = MusicPlayerConfig.getInstance();
         long i = (long)musicplayerconfig.getFadeDurationMs();
         this.soundExecutor.resume(this.manualInstance, 0.0F);
         this.resumeStartMs = monotonicTimeMs();
         this.manualPaused = false;
         if (musicplayerconfig.isFadeOnPause() && i > 0L) {
            this.soundExecutor.setVolume(this.manualInstance, 0.0F);
            this.fading = true;
            this.fadeStartMs = monotonicTimeMs();
            this.fadeDurationMs = Math.max(1L, i);
            this.fadeStartVol = 0.0F;
            this.fadeTargetVol = this.volume;
         } else {
            this.soundExecutor.setVolume(this.manualInstance, this.volume);
         }
      }
   }

   public void setVolume(float var1) {
      this.volume = Math.max(0.0F, Math.min(1.0F, vol));
      if (!this.manualPaused && this.manualInstance != null) {
         this.soundExecutor.setVolume(this.manualInstance, this.volume);
      }
   }

   public float getVolume() {
      return this.volume;
   }

   public void captureAutoMusic(Music var1, SoundInstance var2) {
      if (!this.manualPlaying) {
         if (music != null && instance != null) {
            Holder holder = music.sound();
            if (holder != null && holder.isBound()) {
               SoundEvent soundevent = (SoundEvent)holder.value();
               if (soundevent != null) {
                  this.autoInstance = instance;
                  Identifier identifier = soundevent.location();
                  this.autoTrack = SoundtrackRegistry.getInstance().getTrack(identifier);
                  this.autoStartMs = monotonicTimeMs();
                  this.autoPaused = false;
                  this.autoPausedAtMs = 0L;
                  DebugLog.info("captureAutoMusic: event=%s track=%s", identifier, this.autoTrack != null ? this.autoTrack.title() : "null");
               }
            }
         }
      }
   }

   public void clearAutoMusic() {
      if (this.autoTrack != null) {
         DebugLog.info("clearAutoMusic: clearing \"%s\"", this.autoTrack.title());
      }

      this.autoInstance = null;
      this.autoTrack = null;
      this.autoStartMs = 0L;
      this.autoPaused = false;
      this.autoPausedAtMs = 0L;
   }

   public void captureAutoAsManual() {
      if (this.autoInstance != null && this.autoTrack != null) {
         DebugLog.info("captureAutoAsManual: capturing \"%s\"", this.autoTrack.title());
         SoundInstance soundinstance = this.autoInstance;
         VanillaTrack vanillatrack = this.autoTrack;
         this.clearAutoMusic();
         this.playbackSessionId++;
         this.manualInstance = createTrackInstance(vanillatrack);
         if (this.manualInstance == null) {
            DebugLog.info("captureAutoAsManual: createTrackInstance returned null");
            this.handleFailedTrack(vanillatrack);
         } else {
            this.failedPlaybackAttempts = 0;
            this.lastFailedTrack = null;
            this.manualTrack = vanillatrack;
            this.manualPlaying = true;
            this.manualPaused = false;
            this.accumulatedPlayTime = 0L;
            this.resumeStartMs = monotonicTimeMs();
            this.resetCompletionState();
            this.client.getSoundManager().play(this.manualInstance);
            DebugLog.info("captureAutoAsManual: playing \"%s\" via %s", vanillatrack.title(), this.manualInstance.getClass().getSimpleName());
         }
      }
   }

   public void seekManual(float var1) {
      if (this.manualPlaying && this.manualTrack != null && this.manualInstance != null) {
         Identifier identifier = this.manualTrack.resolvedSoundId();
         if (identifier != null) {
            String s = "sounds/" + identifier.getPath() + ".ogg";
            Identifier identifier1 = Identifier.fromNamespaceAndPath(identifier.getNamespace(), s);
            DebugLog.info("seekManual: track=\"%s\" seekTo=%.1fs variant=%s", this.manualTrack.title(), seconds, identifier);
            new Thread(() -> {
               try {
                  FullPcmDecoder.Result fullpcmdecoder$result = FullPcmDecoder.decodeSeeked(identifier1, seconds);
                  if (fullpcmdecoder$result == null) {
                     DebugLog.info("seekManual: decodeSeeked returned null for %s", identifier1);
                     return;
                  }

                  PcmStream pcmstream = new PcmStream(fullpcmdecoder$result.pcmData(), fullpcmdecoder$result.sampleRate(), fullpcmdecoder$result.channels());
                  boolean flag = this.manualPaused;
                  this.soundExecutor.seek(this.manualInstance, pcmstream, !flag);
                  this.accumulatedPlayTime = (long)(seconds * 1000.0F);
                  this.resumeStartMs = monotonicTimeMs();
                  if (flag) {
                     this.manualPaused = true;
                  }

                  DebugLog.info("seekManual: seek complete to %.1fs", seconds);
               } catch (Exception exception) {
                  DebugLog.info("seekManual: error: %s", exception.getMessage());
               }
            }, "musicplayer-seek").start();
         }
      }
   }

   public boolean isAutoPlaying() {
      if (this.autoInstance == null) {
         return false;
      } else {
         try {
            return this.client.getSoundManager().isActive(this.autoInstance);
         } catch (Exception exception) {
            return false;
         }
      }
   }

   public boolean isAutoPaused() {
      return this.autoPaused;
   }

   public void toggleAutoPause() {
      if (this.autoInstance != null) {
         if (this.autoPaused) {
            this.resumeAuto();
         } else {
            this.pauseAuto();
         }
      }
   }

   private void pauseAuto() {
      if (!this.autoPaused && this.autoInstance != null) {
         this.autoPausedAtMs = monotonicTimeMs() - this.autoStartMs;
         this.soundExecutor.pause(this.autoInstance);
         this.autoPaused = true;
      }
   }

   private void resumeAuto() {
      if (this.autoPaused && this.autoInstance != null) {
         this.soundExecutor.resume(this.autoInstance, this.volume);
         this.autoStartMs = monotonicTimeMs() - this.autoPausedAtMs;
         this.autoPaused = false;
      }
   }

   public boolean isManualPlaying() {
      return this.manualPlaying && !this.manualPaused;
   }

   public boolean isManualPaused() {
      return this.manualPaused;
   }

   public boolean isAnyPlaying() {
      return this.isManualPlaying() || this.isAutoPlaying();
   }

   public boolean isAnyPaused() {
      return this.manualPaused || this.autoPaused;
   }

   public VanillaTrack getCurrentTrack() {
      return this.manualTrack != null ? this.manualTrack : this.autoTrack;
   }

   public SoundInstance getCurrentInstance() {
      return this.manualInstance != null ? this.manualInstance : this.autoInstance;
   }

   public long getElapsedMs() {
      if (this.manualTrack != null) {
         if (this.manualPaused) {
            return this.accumulatedPlayTime;
         } else {
            return !this.manualPlaying ? 0L : this.accumulatedPlayTime + (monotonicTimeMs() - this.resumeStartMs);
         }
      } else if (this.autoTrack != null) {
         return this.autoPaused ? this.autoPausedAtMs : monotonicTimeMs() - this.autoStartMs;
      } else {
         return 0L;
      }
   }

   public long getPlaybackSessionId() {
      return this.playbackSessionId;
   }

   public void tick() {
      try {
         this.tickFade();
         if (this.pendingFailedAdvance) {
            this.pendingFailedAdvance = false;
            if (this.onTrackFinished != null) {
               try {
                  this.onTrackFinished.run();
               } catch (Throwable throwable) {
               }
            }

            return;
         }

         if (this.completionPhase == 0 || this.completionPhase == 1) {
            if (this.manualPlaying && this.manualInstance != null && !this.manualPaused) {
               boolean flag;
               try {
                  flag = this.client.getSoundManager().isActive(this.manualInstance);
               } catch (Exception exception1) {
                  flag = false;
               }

               boolean flag1 = this.soundExecutor.isChannelActive(this.manualInstance);
               boolean flag2 = flag || flag1;
               if (flag2 && !this.confirmedActive) {
                  this.confirmedActive = true;
                  if (this.volume < 1.0F) {
                     this.soundExecutor.setVolume(this.manualInstance, this.volume);
                  }
               } else if (flag2) {
                  this.confirmedActive = true;
               }

               if (!this.confirmedActive) {
                  this.inactiveTickCount = 0;
                  this.completionPhase = 0;
               } else if (monotonicTimeMs() - this.lastPlayAttemptTime < 800L) {
                  this.inactiveTickCount = 0;
                  this.completionPhase = 0;
               } else if (!flag2) {
                  this.inactiveTickCount++;
                  this.completionPhase = 1;
                  if (this.inactiveTickCount >= 3) {
                     DebugLog.info("tick: track completed naturally (inactive for %d ticks, active=%s confirmed=%s)", 3, flag2, this.confirmedActive);
                     this.completionPhase = 2;
                     this.inactiveTickCount = 0;
                  }
               } else {
                  this.inactiveTickCount = 0;
                  this.completionPhase = 0;
               }
            } else {
               this.inactiveTickCount = 0;
               if (this.completionPhase == 1) {
                  this.completionPhase = 0;
               }
            }
         }

         if (this.completionPhase == 2) {
            DebugLog.info("tick: PHASE_CLEANUP clearing manual state");
            this.manualTrack = null;
            this.manualInstance = null;
            this.manualPlaying = false;
            this.manualPaused = false;
            this.lastPlayAttemptTime = 0L;
            this.accumulatedPlayTime = 0L;
            this.resumeStartMs = 0L;
            this.completionPhase = 3;
         }

         if (this.completionPhase == 3) {
            DebugLog.info("tick: PHASE_CALLBACK invoking onTrackFinished");
            this.completionPhase = 0;
            if (this.onTrackFinished != null && !this.callbackExecuted) {
               this.callbackExecuted = true;

               try {
                  this.onTrackFinished.run();
               } catch (Throwable throwable1) {
                  DebugLog.info("tick: onTrackFinished threw %s: %s", throwable1.getClass().getSimpleName(), throwable1.getMessage());
               }
            }
         }

         if (this.manualPlaying && this.manualInstance == null) {
            this.orphanedStateTicks++;
            if (this.orphanedStateTicks > 5) {
               DebugLog.info("tick: orphaned state detected, resetting");
               this.resetPlaybackState();
            }
         } else {
            this.orphanedStateTicks = 0;
         }

         if (this.autoInstance != null && this.autoTrack != null && !this.manualPlaying) {
            boolean flag3;
            try {
               flag3 = this.client.getSoundManager().isActive(this.autoInstance);
            } catch (Exception exception) {
               flag3 = false;
            }

            if (!flag3 && !this.soundExecutor.isChannelActive(this.autoInstance)) {
               DebugLog.info("tick: auto track completed: %s", this.autoTrack.title());
               this.clearAutoMusic();
            }
         }
      } catch (Throwable throwable2) {
         DebugLog.info("tick: caught %s: %s", throwable2.getClass().getSimpleName(), throwable2.getMessage());
      }
   }

   private static long monotonicTimeMs() {
      return System.nanoTime() / 1000000L;
   }

   private void resetPlaybackState() {
      this.clearPCMCache();
      this.manualPlaying = false;
      this.manualPaused = false;
      this.pendingPause = false;
      this.manualInstance = null;
      this.manualTrack = null;
      this.accumulatedPlayTime = 0L;
      this.resumeStartMs = 0L;
      this.resetCompletionState();
   }

   private void resetCompletionState() {
      this.completionPhase = 0;
      this.inactiveTickCount = 0;
      this.callbackExecuted = false;
      this.confirmedActive = false;
   }

   public void startFade(float var1, long var2) {
      if (this.isAnyPlaying()) {
         this.fadeStartVol = this.getEffectiveVolume();
         this.fadeTargetVol = targetVolume;
         this.fadeDurationMs = Math.max(1L, durationMs);
         this.fadeStartMs = monotonicTimeMs();
         this.fading = true;
      }
   }

   public void duck(float var1, long var2, float var4) {
      if (this.isAnyPlaying()) {
         this.ducking = true;
         this.duckRestoreVolume = restoreVolume;
         this.startFade(duckVolume, fadeLengthMs);
      }
   }

   public boolean isDucking() {
      return this.ducking || this.fading;
   }

   public boolean hasFading() {
      return this.fading;
   }

   private float getEffectiveVolume() {
      SoundInstance soundinstance = this.manualInstance != null ? this.manualInstance : this.autoInstance;
      return soundinstance == null ? this.volume : this.volume * this.client.options.getSoundSourceVolume(soundinstance.getSource());
   }

   public void tickFade() {
      if (this.fading) {
         long i = monotonicTimeMs() - this.fadeStartMs;
         float f = Math.min(1.0F, (float)i / (float)this.fadeDurationMs);
         float f1 = this.fadeStartVol + (this.fadeTargetVol - this.fadeStartVol) * f;
         SoundInstance soundinstance = this.manualInstance != null ? this.manualInstance : this.autoInstance;
         if (soundinstance != null) {
            this.soundExecutor.setVolume(soundinstance, f1);
         }

         if (f >= 1.0F) {
            this.fading = false;
            if (this.pendingPause) {
               this.pendingPause = false;
               if (this.manualPlaying && this.manualInstance != null) {
                  long j = this.accumulatedPlayTime + (monotonicTimeMs() - this.resumeStartMs);
                  this.accumulatedPlayTime = j;
                  this.soundExecutor.pause(this.manualInstance);
                  this.manualPaused = true;
               }
            } else if (this.ducking) {
               this.ducking = false;
               this.startFade(this.duckRestoreVolume, this.fadeDurationMs);
            } else if (this.fadeTargetVol <= 0.001F) {
               if (this.manualPlaying) {
                  this.stopManual();
               } else {
                  this.clearAutoMusic();
               }
            }
         }
      }
   }

   private static SoundInstance createTrackInstance(VanillaTrack var0) {
      if (track == null) {
         return null;
      } else {
         return (SoundInstance)(track.resolvedSoundId() != null && SoundtrackRegistry.getInstance().hasMultipleVariants(track.soundIdentifier())
            ? new FixedSoundInstance(track.soundIdentifier(), track.resolvedSoundId())
            : createMusicInstance(track.soundIdentifier()));
      }
   }

   private static SimpleSoundInstance createMusicInstance(Identifier var0) {
      return createMusicInstance(soundId, 0);
   }

   private static SimpleSoundInstance createMusicInstance(Identifier var0, int var1) {
      if (depth > 10) {
         return null;
      } else {
         try {
            Minecraft minecraft = Minecraft.getInstance();
            SoundManager soundmanager = minecraft.getSoundManager();
            WeighedSoundEvents weighedsoundevents = soundmanager.getSoundEvent(soundId);
            if (weighedsoundevents != null && weighedsoundevents != SoundManager.INTENTIONALLY_EMPTY_SOUND_EVENT) {
               List list = ((WeighedSoundEventsAccessor)weighedsoundevents).overtune$getList();
               if (list != null && !list.isEmpty()) {
                  boolean flag = false;

                  for (Weighted weighted : list) {
                     if (weighted.getWeight() > 0 && isCreateEntryValid(weighted, minecraft, depth)) {
                        flag = true;
                        break;
                     }
                  }

                  return !flag
                     ? null
                     : new SimpleSoundInstance(soundId, SoundSource.MUSIC, 1.0F, 1.0F, RandomSource.create(), false, 0, Attenuation.NONE, 0.0, 0.0, 0.0, true);
               } else {
                  return null;
               }
            } else {
               return null;
            }
         } catch (Exception exception) {
            return null;
         }
      }
   }

   private static boolean isCreateEntryValid(Weighted<Sound> var0, Minecraft var1, int var2) {
      try {
         if (!(entry instanceof WeighedSoundEvents weighedsoundevents)) {
            Sound sound = (Sound)entry.getSound(RandomSource.create());
            if (sound != null && sound != SoundManager.EMPTY_SOUND && sound != SoundManager.INTENTIONALLY_EMPTY_SOUND) {
               if (sound.getType() == Type.SOUND_EVENT) {
                  return createMusicInstance(sound.getLocation(), depth + 1) != null;
               } else {
                  Identifier identifier = sound.getLocation();
                  Identifier identifier1 = Identifier.fromNamespaceAndPath(identifier.getNamespace(), "sounds/" + identifier.getPath() + ".ogg");
                  Optional optional = mc.getResourceManager().getResource(identifier1);
                  return optional.isPresent();
               }
            } else {
               return false;
            }
         } else {
            List list = ((WeighedSoundEventsAccessor)weighedsoundevents).overtune$getList();
            if (list != null && !list.isEmpty()) {
               boolean flag = false;

               for (Weighted weighted : list) {
                  if (weighted.getWeight() > 0 && isCreateEntryValid(weighted, mc, depth + 1)) {
                     flag = true;
                     break;
                  }
               }

               return flag;
            } else {
               return false;
            }
         }
      } catch (Exception exception) {
         return false;
      }
   }
}
