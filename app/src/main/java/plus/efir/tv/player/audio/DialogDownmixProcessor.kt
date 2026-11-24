package plus.efir.tv.player.audio

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Downmix 5.1/7.1 многоканального звука в стерео с усилением центрального канала (речь и диалоги).
 *   L = FL + c·FC + s·SL
 *   R = FR + c·FC + s·SR
 *   LFE отбрасывается, чтобы не перегружать динамики ТВ.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class DialogDownmixProcessor(private val liveProfile: LiveAudioProfile) : BaseAudioProcessor() {

    private var centerGain = CENTER_BASE
    private var smoothingCoef = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!isSupportedEncoding(inputAudioFormat.encoding)) {
            return AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount != 6 && inputAudioFormat.channelCount != 8) {
            return AudioFormat.NOT_SET
        }
        smoothingCoef = onePoleCoefficient(PARAMETER_SMOOTHING_SECONDS, inputAudioFormat.sampleRate)
        return AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val encoding = inputAudioFormat.encoding
        val inChannels = inputAudioFormat.channelCount
        val inFrameBytes = inputAudioFormat.bytesPerFrame
        val sampleBytes = inFrameBytes / inChannels
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        val frames = (end - start) / inFrameBytes
        val output = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)

        val is71 = inChannels == 8
        val surroundSum = if (is71) 2 * SURROUND_GAIN else SURROUND_GAIN
        val targetCenter = targetCenterGain()
        var center = centerGain

        for (frame in 0 until frames) {
            val base = start + frame * inFrameBytes
            val fl = inputBuffer.readSample(base, encoding)
            val fr = inputBuffer.readSample(base + sampleBytes, encoding)
            val fc = inputBuffer.readSample(base + 2 * sampleBytes, encoding)
            var sl = inputBuffer.readSample(base + 4 * sampleBytes, encoding)
            var sr = inputBuffer.readSample(base + 5 * sampleBytes, encoding)
            if (is71) {
                sl += inputBuffer.readSample(base + 6 * sampleBytes, encoding)
                sr += inputBuffer.readSample(base + 7 * sampleBytes, encoding)
            }

            center = smoothingCoef * center + (1f - smoothingCoef) * targetCenter
            val normalization = 1f / (1f + center + surroundSum)
            output.writeSample((fl + center * fc + SURROUND_GAIN * sl) * normalization, encoding)
            output.writeSample((fr + center * fc + SURROUND_GAIN * sr) * normalization, encoding)
        }

        centerGain = center
        inputBuffer.position(end)
        output.flip()
    }

    override fun onFlush() {
        centerGain = targetCenterGain()
    }

    private fun targetCenterGain(): Float = CENTER_BASE * dbToLinear(liveProfile.current.dialogGainDb)

    private companion object {
        const val CENTER_BASE = 0.707f
        const val SURROUND_GAIN = 0.5f
    }
}
