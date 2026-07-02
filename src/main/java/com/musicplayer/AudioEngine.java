package com.musicplayer;

import com.musicplayer.player.OpenALAudioPlayer;
import com.musicplayer.player.FullPcmDecoder;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * AudioEngine using OpenAL instead of javax.sound.sampled.
 * Compatible with PC and Android (Pojav/MJ Launcher).
 */
public class AudioEngine {

    private PlaybackSlot primary;
    private PlaybackSlot crossfading;
    private float volume = 1.0f;
    private float playbackSpeed = 1.0f;
    private long durationMs = 0;
    private long positionMs = 0;
    private File currentFile;
    private Runnable onFinished;
    private Runnable onTrackStart;
    private Runnable pendingCallback;

    private static final int VIZ_SAMPLES = 512;
    private float[] vizBuffer = new float[VIZ_SAMPLES];
    private float[] vizSmooth = new float[32];

    // Ducking fields...
    private DuckStage duckStage = DuckStage.NONE;
    private long duckStageStartMs = 0;
    private long duckHoldDurationMs = 0;
    private float duckStartMultiplier = 1.0f;
    private float duckTargetMultiplier = 1.0f;
    private float duckRestoreMultiplier = 1.0f;
    private float duckMultiplier = 1.0f;
    private static final float DUCK_FADE_MS = 200.0f;

    public AudioEngine() {
        this.primary = new PlaybackSlot();
        this.crossfading = new PlaybackSlot();
    }

    // ... other methods remain similar, but using OpenAL ...

    public void play(File file, long seekMs) {
        this.currentFile = file;
        this.positionMs = seekMs;
        primary.play(file, seekMs, false);
    }

    public void startCrossfade(File file, long seekMs, int fadeMs) {
        crossfading.play(file, seekMs, true);
        crossfading.startFade(1.0f, fadeMs);
        primary.startFade(0.0f, fadeMs);
    }

    public void stop() {
        primary.cleanup();
        crossfading.cleanup();
    }

    public void stopPrimary() {
        primary.cleanup();
    }

    public void togglePause() {
        primary.togglePause();
    }

    public void setVolume(float vol) {
        this.volume = vol;
        primary.setVolume(vol);
        crossfading.setVolume(vol);
    }

    public float getVolume() { return volume; }
    public float getPlaybackSpeed() { return playbackSpeed; }
    public boolean isActive() { return primary.isActive(); }
    public boolean isPaused() { return primary.isPaused(); }
    public long getDurationMs() { return durationMs; }
    public long getPositionMs() { return primary.getPositionMs(); }

    public void setPlaybackSpeed(float speed) {
        this.playbackSpeed = speed;
        // OpenAL pitch control: AL_PITCH
        primary.setPitch(speed);
        crossfading.setPitch(speed);
    }

    // Visualizer methods remain the same (read from PCM buffer)
    public void getVisualizerData(float[] out) {
        // ... existing implementation ...
    }

    public void getVisualizerBars(float[] bars, int count) {
        // ... existing implementation ...
    }

    // Ducking methods remain similar
    public void duck(float target, long holdMs, float restore) {
        // ... existing implementation ...
    }

    public void tickDuck() {
        // ... existing implementation ...
    }

    public boolean isDucking() {
        return duckStage != DuckStage.NONE;
    }

    // ...

    /**
     * PlaybackSlot using OpenAL instead of SourceDataLine.
     */
    private class PlaybackSlot {
        private OpenALAudioPlayer player;
        private Thread thread;
        private boolean playing = false;
        private boolean pause = false;
        private boolean stopReq = false;
        private float fadeVolume = 1.0f;
        private boolean fading = false;
        private float fadeTarget = 1.0f;
        private long fadeStartMs = 0;
        private long fadeDurationMs = 0;
        private float fadeStartVol = 1.0f;
        private long durationMs = 0;
        private long positionMs = 0;
        private Runnable onFinished;
        private Runnable onTrackStart;

        // PCM data for visualizer
        private ByteBuffer pcmData;
        private int pcmSampleRate = 44100;
        private int pcmChannels = 2;

        public PlaybackSlot() {}

        public void play(File file, long seekMs, boolean isCrossfade) {
            cleanup();
            this.stopReq = false;
            this.pause = false;
            this.playing = true;

            thread = new Thread(() -> runPlayback(file, seekMs));
            thread.setDaemon(true);
            thread.start();
        }

        public void interrupt() {
            stopReq = true;
            if (thread != null) {
                thread.interrupt();
            }
            cleanup();
        }

        public void startFade(float target, long durationMs) {
            this.fading = true;
            this.fadeTarget = target;
            this.fadeStartMs = System.currentTimeMillis();
            this.fadeDurationMs = durationMs;
            this.fadeStartVol = fadeVolume;
        }

        public void applyVolume(float masterVol) {
            if (player != null) {
                player.setVolume(masterVol * fadeVolume);
            }
        }

        public void setVolume(float vol) {
            if (player != null) {
                player.setVolume(vol * fadeVolume);
            }
        }

        public void setPitch(float pitch) {
            // OpenAL pitch control
            if (player != null && player.getSourceId() != -1) {
                org.lwjgl.openal.AL10.alSourcef(player.getSourceId(), org.lwjgl.openal.AL10.AL_PITCH, pitch);
            }
        }

        public void togglePause() {
            if (player != null) {
                if (player.isPlaying()) {
                    player.pause();
                    pause = true;
                } else if (player.isPaused()) {
                    player.play();
                    pause = false;
                }
            }
        }

        public boolean isActive() {
            return playing || (player != null && (player.isPlaying() || player.isPaused()));
        }

        public boolean isPaused() {
            return pause;
        }

        public long getPositionMs() {
            if (player != null) {
                return (long)(player.getPlaybackPositionSeconds() * 1000);
            }
            return 0;
        }

        public void cleanup() {
            stopReq = true;
            playing = false;
            if (player != null) {
                player.cleanup();
                player = null;
            }
            if (thread != null) {
                thread.interrupt();
                thread = null;
            }
        }

        private void runPlayback(File file, long seekMs) {
            try {
                if (onTrackStart != null) {
                    onTrackStart.run();
                }

                String name = file.getName().toLowerCase();
                if (name.endsWith(".mp3")) {
                    playMp3(file, seekMs);
                } else if (name.endsWith(".wav")) {
                    playWav(file, seekMs);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                cleanup();
                if (onFinished != null && !stopReq) {
                    onFinished.run();
                }
            }
        }

        private void playMp3(File file, long seekMs) throws Exception {
            // Decode MP3 to PCM using JLayer
            FileInputStream fis = new FileInputStream(file);
            byte[] fileBytes = fis.readAllBytes();
            fis.close();

            Bitstream bitstream = new Bitstream(new ByteArrayInputStream(fileBytes));
            Decoder decoder = new Decoder();

            // Skip frames for seeking
            if (seekMs > 0) {
                Header h = bitstream.readFrame();
                if (h != null) {
                    float frameDuration = h.ms_per_frame();
                    int framesToSkip = (int)(seekMs / frameDuration);
                    for (int i = 0; i < framesToSkip; i++) {
                        bitstream.closeFrame();
                        h = bitstream.readFrame();
                        if (h == null) break;
                    }
                }
            }

            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
            Header firstHeader = null;

            while (!stopReq) {
                Header h = bitstream.readFrame();
                if (h == null) break;
                if (firstHeader == null) {
                    firstHeader = h;
                    pcmSampleRate = h.frequency();
                    pcmChannels = (h.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;
                }

                SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                short[] samples = buffer.getBuffer();
                int len = buffer.getBufferLength();

                for (int i = 0; i < len; i++) {
                    pcmOut.write(samples[i] & 0xFF);
                    pcmOut.write((samples[i] >> 8) & 0xFF);
                }

                bitstream.closeFrame();
            }

            bitstream.close();

            byte[] pcmBytes = pcmOut.toByteArray();
            if (pcmBytes.length == 0) return;

            pcmData = ByteBuffer.allocateDirect(pcmBytes.length).order(ByteOrder.nativeOrder());
            pcmData.put(pcmBytes);
            pcmData.flip();

            // Calculate duration
            int bytesPerFrame = pcmChannels * 2; // 16-bit
            int totalFrames = pcmBytes.length / bytesPerFrame;
            durationMs = (long)((totalFrames / (float)pcmSampleRate) * 1000);

            // Play via OpenAL
            player = new OpenALAudioPlayer();
            player.init(pcmSampleRate, pcmChannels);
            player.bufferData(pcmData);
            player.setVolume(AudioEngine.this.volume * fadeVolume);
            player.play();

            // Wait for playback to finish
            while (!stopReq && player.isPlaying()) {
                Thread.sleep(50);

                // Update visualizer buffer
                if (pcmData != null) {
                    updateVisualizerFromPcm();
                }

                // Handle pause
                if (pause && player.isPlaying()) {
                    player.pause();
                } else if (!pause && player.isPaused()) {
                    player.play();
                }

                // Handle fade
                if (fading) {
                    tickFade();
                }
            }
        }

        private void playWav(File file, long seekMs) throws Exception {
            FileInputStream fis = new FileInputStream(file);
            byte[] wavBytes = fis.readAllBytes();
            fis.close();

            // Parse WAV header
            if (wavBytes.length < 44) return;

            // RIFF header: bytes 0-3 = "RIFF", 4-7 = file size
            // WAVE header: bytes 8-11 = "WAVE"
            // fmt chunk: bytes 12-15 = "fmt ", 16-19 = chunk size
            int audioFormat = (wavBytes[20] & 0xFF) | ((wavBytes[21] & 0xFF) << 8);
            pcmChannels = (wavBytes[22] & 0xFF) | ((wavBytes[23] & 0xFF) << 8);
            pcmSampleRate = (wavBytes[24] & 0xFF) | ((wavBytes[25] & 0xFF) << 8) | 
                           ((wavBytes[26] & 0xFF) << 16) | ((wavBytes[27] & 0xFF) << 24);
            int byteRate = (wavBytes[28] & 0xFF) | ((wavBytes[29] & 0xFF) << 8) | 
                          ((wavBytes[30] & 0xFF) << 16) | ((wavBytes[31] & 0xFF) << 24);
            int blockAlign = (wavBytes[32] & 0xFF) | ((wavBytes[33] & 0xFF) << 8);
            int bitsPerSample = (wavBytes[34] & 0xFF) | ((wavBytes[35] & 0xFF) << 8);

            // Find data chunk
            int dataOffset = 36;
            while (dataOffset < wavBytes.length - 8) {
                String chunkId = new String(wavBytes, dataOffset, 4, java.nio.charset.StandardCharsets.US_ASCII);
                int chunkSize = (wavBytes[dataOffset+4] & 0xFF) | ((wavBytes[dataOffset+5] & 0xFF) << 8) |
                               ((wavBytes[dataOffset+6] & 0xFF) << 16) | ((wavBytes[dataOffset+7] & 0xFF) << 24);
                if (chunkId.equals("data")) {
                    dataOffset += 8;
                    break;
                }
                dataOffset += 8 + chunkSize;
            }

            int dataSize = wavBytes.length - dataOffset;
            byte[] pcmBytes = new byte[dataSize];
            System.arraycopy(wavBytes, dataOffset, pcmBytes, 0, dataSize);

            // Handle seek
            if (seekMs > 0) {
                int bytesPerSecond = pcmSampleRate * pcmChannels * (bitsPerSample / 8);
                int skipBytes = (int)((seekMs / 1000.0f) * bytesPerSecond);
                skipBytes = Math.min(skipBytes, pcmBytes.length);
                byte[] seeked = new byte[pcmBytes.length - skipBytes];
                System.arraycopy(pcmBytes, skipBytes, seeked, 0, seeked.length);
                pcmBytes = seeked;
            }

            pcmData = ByteBuffer.allocateDirect(pcmBytes.length).order(ByteOrder.nativeOrder());
            pcmData.put(pcmBytes);
            pcmData.flip();

            // Calculate duration
            int bytesPerFrame = pcmChannels * (bitsPerSample / 8);
            int totalFrames = pcmBytes.length / bytesPerFrame;
            durationMs = (long)((totalFrames / (float)pcmSampleRate) * 1000);

            // Play via OpenAL
            player = new OpenALAudioPlayer();
            player.init(pcmSampleRate, pcmChannels);
            player.bufferData(pcmData);
            player.setVolume(AudioEngine.this.volume * fadeVolume);
            player.play();

            // Wait for playback
            while (!stopReq && player.isPlaying()) {
                Thread.sleep(50);

                if (pcmData != null) {
                    updateVisualizerFromPcm();
                }

                if (pause && player.isPlaying()) {
                    player.pause();
                } else if (!pause && player.isPaused()) {
                    player.play();
                }

                if (fading) {
                    tickFade();
                }
            }
        }

        private void tickFade() {
            long now = System.currentTimeMillis();
            float progress = Math.min(1.0f, (now - fadeStartMs) / (float)fadeDurationMs);
            fadeVolume = fadeStartVol + (fadeTarget - fadeStartVol) * progress;

            if (player != null) {
                player.setVolume(AudioEngine.this.volume * fadeVolume);
            }

            if (progress >= 1.0f) {
                fading = false;
                if (fadeTarget <= 0.01f) {
                    stopReq = true;
                }
            }
        }

        private void updateVisualizerFromPcm() {
            // Read current position from PCM and update vizBuffer
            // This is a simplified version - original had more complex DSP
            if (pcmData == null) return;

            int pos = (int)(player.getPlaybackPositionSeconds() * pcmSampleRate * pcmChannels * 2);
            pos = Math.min(pos, pcmData.capacity() - VIZ_SAMPLES * 4);
            pos = Math.max(0, pos);

            pcmData.position(pos);
            for (int i = 0; i < VIZ_SAMPLES && pcmData.remaining() >= 2; i++) {
                short sample = pcmData.getShort();
                vizBuffer[i] = sample / 32768.0f;
            }
        }
    }

    private enum DuckStage {
        NONE, FADE_OUT, HOLD, FADE_IN
    }
}
