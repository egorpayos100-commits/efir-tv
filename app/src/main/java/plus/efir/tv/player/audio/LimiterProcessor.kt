package plus.efir.tv.player.audio

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Пиковый лимитер с lookahead (~5 мс):
 * Защищает от перегрузок и хрипов динамиков при подъеме громкости, удерживая уровень на -1 dBFS.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class LimiterProcessor(private val liveProfile: LiveAudioProfile) : BaseAudioProcessor() {

    private var lookaheadFrames = 0
    private var channels = 0
    private var releaseCoef = 0f

    private var delayLine = FloatArray(0)
    private var minValues = FloatArray(0)
    private var minFrameIndex = LongArray(0)
    private var minHead = 0
    private var minSize = 0

    private var boxValues = FloatArray(0)
    private var boxSum = 0.0

    private var released = 1f
    private var frameCounter = 0L
    private var frameScratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!liveProfile.filmAudioProcessing || !isSupportedEncoding(inputAudioFormat.encoding)) {
            return AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        lookaheadFrames = max(2, (LOOKAHEAD_SECONDS * inputAudioFormat.sampleRate).roundToInt())
        releaseCoef = onePoleCoefficient(RELEASE_SECONDS, inputAudioFormat.sampleRate)
        delayLine = FloatArray(lookaheadFrames * channels)
        minValues = FloatArray(lookaheadFrames)
        minFrameIndex = LongArray(lookaheadFrames)
        boxValues = FloatArray(lookaheadFrames)
        frameScratch = FloatArray(channels)
        resetState()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (!liveProfile.filmAudioProcessing) {
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
            return
        }
        val encoding = inputAudioFormat.encoding
        val sampleBytes = inputAudioFormat.bytesPerFrame / channels
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        val output = replaceOutputBuffer(end - start)
        val ceiling = currentCeiling()

        var frameStart = start
        while (frameStart < end) {
            for (ch in 0 until channels) {
                frameScratch[ch] = inputBuffer.readSample(frameStart + ch * sampleBytes, encoding)
            }
            processFrame(output, encoding, ceiling)
            frameStart += inputAudioFormat.bytesPerFrame
        }
        inputBuffer.position(end)
        output.flip()
    }

    override fun onQueueEndOfStream() {
        if (!liveProfile.filmAudioProcessing) return
        val pendingFrames = lookaheadFrames - 1
        val output = replaceOutputBuffer(pendingFrames * inputAudioFormat.bytesPerFrame)
        val ceiling = currentCeiling()
        frameScratch.fill(0f)
        repeat(pendingFrames) { processFrame(output, inputAudioFormat.encoding, ceiling) }
        output.flip()
    }

    override fun onFlush() = resetState()

    private fun processFrame(output: ByteBuffer, encoding: Int, ceiling: Float) {
        val n = frameCounter
        val window = lookaheadFrames

        var peak = 0f
        for (ch in 0 until channels) {
            val a = abs(frameScratch[ch])
            if (a > peak) peak = a
        }
        val neededGain = if (peak > ceiling) ceiling / peak else 1f

        while (minSize > 0 && minValues[minHead] <= neededGain) {
            minHead = (minHead + 1) % window
            minSize--
        }
        val insertPos = (minHead + minSize) % window
        minValues[insertPos] = neededGain
        minFrameIndex[insertPos] = n
        minSize++

        val oldestAllowedIndex = n - window + 1
        while (minSize > 0 && minFrameIndex[minHead] < oldestAllowedIndex) {
            minHead = (minHead + 1) % window
            minSize--
        }
        val m = minValues[minHead]

        released = if (m < released) m else releaseCoef * released + (1f - releaseCoef) * m

        val boxPos = (n % window).toInt()
        boxSum += released - boxValues[boxPos]
        boxValues[boxPos] = released
        val smoothedGain = (boxSum / window).toFloat()

        val delayPos = ((n % window).toInt()) * channels
        for (ch in 0 until channels) {
            val delayedSample = delayLine[delayPos + ch]
            delayLine[delayPos + ch] = frameScratch[ch]
            val limited = (delayedSample * smoothedGain).coerceIn(-ceiling, ceiling)
            output.writeSample(limited, encoding)
        }

        frameCounter++
    }

    private fun currentCeiling(): Float =
        if (liveProfile.current.limiterEnabled) dbToLinear(AudioProfile.LIMITER_CEILING_DB) else Float.MAX_VALUE

    private fun resetState() {
        delayLine.fill(0f)
        boxValues.fill(1f)
        boxSum = lookaheadFrames.toDouble()
        minHead = 0
        minSize = 0
        released = 1f
        frameCounter = 0L
    }

    private companion object {
        const val LOOKAHEAD_SECONDS = 0.005f
        const val RELEASE_SECONDS = 0.080f
    }
}
