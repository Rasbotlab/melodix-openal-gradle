package com.musicplayer.player;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.client.sounds.AudioStream;

/**
 * PCM audio stream using explicit format parameters instead of javax.sound.sampled.AudioFormat.
 * Compatible with OpenAL and Android (Pojav/MJ Launcher).
 */
public class PcmStream implements AudioStream {
    private final ByteBuffer data;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final boolean bigEndian;

    public PcmStream(ByteBuffer data, int sampleRate, int channels, int bitsPerSample, boolean bigEndian) {
        this.data = data;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.bigEndian = bigEndian;
    }

    /** Legacy constructor for compatibility - assumes 16-bit, little-endian, stereo */
    public PcmStream(ByteBuffer data, int sampleRate, int channels) {
        this(data, sampleRate, channels, 16, false);
    }

    public int getSampleRate() {
        return this.sampleRate;
    }

    public int getChannels() {
        return this.channels;
    }

    public int getBitsPerSample() {
        return this.bitsPerSample;
    }

    public boolean isBigEndian() {
        return this.bigEndian;
    }

    /** @deprecated Use getSampleRate(), getChannels(), getBitsPerSample() instead */
    @Deprecated
    public javax.sound.sampled.AudioFormat getFormat() {
        throw new UnsupportedOperationException(
            "AudioFormat is not available in OpenAL builds. " +
            "Use getSampleRate(), getChannels(), getBitsPerSample() instead."
        );
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        if (!this.data.hasRemaining()) {
            return null;
        } else {
            int i = Math.min(size, this.data.remaining());
            int j = this.data.position();
            this.data.position(j + i);
            return this.data.slice(j, i).order(this.data.order());
        }
    }

    @Override
    public void close() throws IOException {
    }
}
