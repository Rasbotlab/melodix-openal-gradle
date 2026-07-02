package com.musicplayer.player;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * OpenAL-based audio player that replaces javax.sound.sampled.SourceDataLine.
 * Uses LWJGL OpenAL (AL10) which is available in both PC and Android (Pojav/MJ Launcher) Minecraft.
 */
public class OpenALAudioPlayer {
    private int sourceId = -1;
    private int bufferId = -1;
    private boolean playing = false;
    private boolean paused = false;
    private float volume = 1.0f;
    private float pitch = 1.0f;
    private int sampleRate = 44100;
    private int channels = 2;

    /**
     * Initialize OpenAL source and buffer.
     * @param sampleRate Sample rate in Hz (e.g., 44100)
     * @param channels Number of channels (1 = mono, 2 = stereo)
     */
    public void init(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;

        this.bufferId = AL10.alGenBuffers();
        this.sourceId = AL10.alGenSources();

        AL10.alSourcef(this.sourceId, AL10.AL_PITCH, this.pitch);
        AL10.alSourcef(this.sourceId, AL10.AL_GAIN, this.volume);
        AL10.alSource3f(this.sourceId, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
        AL10.alSource3f(this.sourceId, AL10.AL_VELOCITY, 0.0f, 0.0f, 0.0f);
        AL10.alSourcei(this.sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);
    }

    /**
     * Upload PCM data to OpenAL buffer and attach to source.
     * @param pcmData ByteBuffer containing 16-bit PCM data (little-endian)
     */
    public void bufferData(ByteBuffer pcmData) {
        int format = (this.channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

        // Ensure native byte order for OpenAL
        if (pcmData.order() != ByteOrder.nativeOrder()) {
            ByteBuffer reordered = ByteBuffer.allocateDirect(pcmData.remaining()).order(ByteOrder.nativeOrder());
            ByteBuffer dup = pcmData.duplicate();
            reordered.put(dup);
            reordered.flip();
            pcmData = reordered;
        }

        AL10.alBufferData(this.bufferId, format, pcmData, this.sampleRate);
        AL10.alSourcei(this.sourceId, AL10.AL_BUFFER, this.bufferId);
    }

    public void play() {
        if (this.sourceId != -1) {
            AL10.alSourcePlay(this.sourceId);
            this.playing = true;
            this.paused = false;
        }
    }

    public void pause() {
        if (this.sourceId != -1) {
            AL10.alSourcePause(this.sourceId);
            this.playing = false;
            this.paused = true;
        }
    }

    public void stop() {
        if (this.sourceId != -1) {
            AL10.alSourceStop(this.sourceId);
            this.playing = false;
            this.paused = false;
        }
    }

    public void setVolume(float vol) {
        this.volume = Math.max(0.0f, Math.min(1.0f, vol));
        if (this.sourceId != -1) {
            AL10.alSourcef(this.sourceId, AL10.AL_GAIN, this.volume);
        }
    }

    public float getVolume() {
        return this.volume;
    }

    public void setPitch(float pitch) {
        this.pitch = Math.max(0.5f, Math.min(2.0f, pitch));
        if (this.sourceId != -1) {
            AL10.alSourcef(this.sourceId, AL10.AL_PITCH, this.pitch);
        }
    }

    public float getPitch() {
        return this.pitch;
    }

    public boolean isPlaying() {
        if (this.sourceId == -1) return false;
        int state = AL10.alGetSourcei(this.sourceId, AL10.AL_SOURCE_STATE);
        return state == AL10.AL_PLAYING;
    }

    public boolean isPaused() {
        if (this.sourceId == -1) return false;
        int state = AL10.alGetSourcei(this.sourceId, AL10.AL_SOURCE_STATE);
        return state == AL10.AL_PAUSED;
    }

    /**
     * Get current playback position in milliseconds.
     */
    public long getPositionMs() {
        if (this.sourceId == -1) return 0;
        float seconds = AL10.alGetSourcef(this.sourceId, AL11.AL_SEC_OFFSET);
        return (long)(seconds * 1000.0f);
    }

    /**
     * Set playback position in seconds.
     */
    public void setPositionSeconds(float seconds) {
        if (this.sourceId != -1) {
            AL10.alSourcef(this.sourceId, AL11.AL_SEC_OFFSET, seconds);
        }
    }

    public int getSourceId() {
        return this.sourceId;
    }

    public int getBufferId() {
        return this.bufferId;
    }

    public int getSampleRate() {
        return this.sampleRate;
    }

    public int getChannels() {
        return this.channels;
    }

    /**
     * Clean up OpenAL resources.
     */
    public void cleanup() {
        if (this.sourceId != -1) {
            AL10.alSourceStop(this.sourceId);
            AL10.alDeleteSources(this.sourceId);
            this.sourceId = -1;
        }
        if (this.bufferId != -1) {
            AL10.alDeleteBuffers(this.bufferId);
            this.bufferId = -1;
        }
        this.playing = false;
        this.paused = false;
    }
}
