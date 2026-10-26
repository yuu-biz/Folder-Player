package com.wing.folderplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Left and right swapped (1.8.0), for every decoder: it sits in the player's audio sink ahead of the speed change.
 * It stays active for all stereo PCM and swaps while [enabled], so turning it on or off needs no new audio track; other
 * channel counts pass through.
 */
@OptIn(UnstableApi::class)
class ChannelSwapAudioProcessor : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.channelCount == 2 && inputAudioFormat.encoding in SWAPPABLE) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        if (enabled) swap(inputBuffer, out, inputAudioFormat.bytesPerFrame) else out.put(inputBuffer)
        out.flip()
    }

    companion object {
        /** Read by the audio thread on every buffer; set from the player's screen and when the service starts. */
        @Volatile var enabled = false

        private val SWAPPABLE = setOf(
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT,
        )

        /** Copies whole frames of [input] to [output] with the two samples of each frame exchanged. */
        fun swap(input: ByteBuffer, output: ByteBuffer, bytesPerFrame: Int) {
            val sample = bytesPerFrame / 2
            while (input.remaining() >= bytesPerFrame) {
                val frame = input.position()
                for (i in 0 until sample) output.put(input.get(frame + sample + i))
                for (i in 0 until sample) output.put(input.get(frame + i))
                input.position(frame + bytesPerFrame)
            }
            // A partial frame cannot be swapped; it is passed on as it is (the sink only sends whole frames).
            output.put(input)
        }
    }
}
