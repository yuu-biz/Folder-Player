package com.wing.folderplayer.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Left and right exchanged frame by frame, for 16-bit and 32-bit (float) samples; a cut-off frame passes as it is. */
class ChannelSwapAudioProcessorTest {
    private fun swapped(bytes: ByteArray, bytesPerFrame: Int): ByteArray {
        val out = ByteBuffer.allocate(bytes.size)
        ChannelSwapAudioProcessor.swap(ByteBuffer.wrap(bytes), out, bytesPerFrame)
        return out.array()
    }

    @Test fun sixteenBitFrames() {
        fun pcm(vararg s: Int) = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { s.forEach { putShort(it.toShort()) } }.array()
        assertArrayEquals(pcm(0x0304, 0x0102, 0x0708, 0x0506), swapped(pcm(0x0102, 0x0304, 0x0506, 0x0708), 4))
    }

    @Test fun floatFrames() {
        fun pcm(vararg f: Float) = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { f.forEach { putFloat(it) } }.array()
        assertArrayEquals(pcm(-0.5f, 0.25f, 1f, 0f), swapped(pcm(0.25f, -0.5f, 0f, 1f), 8))
    }

    @Test fun aCutOffFramePassesAsItIs() {
        assertArrayEquals(byteArrayOf(3, 4, 1, 2, 9, 9), swapped(byteArrayOf(1, 2, 3, 4, 9, 9), 4))
    }
}
