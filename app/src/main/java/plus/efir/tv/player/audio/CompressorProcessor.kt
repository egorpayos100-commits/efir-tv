package plus.efir.tv.player.audio

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.pow

/**
 * Динамический компрессор (Dynamic Range Compression):
 *  - Измеряет RMS мощность сигнала (~10 мс окно)
 *  - Сглаживает громкие пики (взрывы, выстрелы, эффекты)
 *  - Поднимает тихие места (диалоги, шепот) через makeup-gain
 *  - Мягкий изгиб (knee) 6 dB без щелчков и артефактов
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class CompressorProcessor(private val liveProfile: LiveAudioProfile) : BaseAudioProcessor() {

    private var detectorCoef = 0f
    private var smoothingCoef = 0f

    private var coefProfile: AudioProfile? = null
    private var attackCoef = 0f
    private var releaseCoef = 0f

    private var meanSquare = 0f
    private var gainReductionDb = 0f
    private var makeupDb = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!isSupportedEncoding(inputAudioFormat.encoding)) return AudioFormat.NOT_SET
        detectorCoef = onePoleCoefficient(RMS_WINDOW_SECONDS, inputAudioFormat.sampleRate)
        smoothingCoef = onePoleCoefficient(PARAMETER_SMOOTHING_SECONDS, inputAudioFormat.sampleRate)
        coefProfile = null
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val profile = liveProfile.current
        if (profile !== coefProfile) {
            attackCoef = onePoleCoefficient(profile.attackMs / 1000f, inputAudioFormat.sampleRate)
            releaseCoef = onePoleCoefficient(profile.releaseMs / 1000f, inputAudioFormat.sampleRate)
            coefProfile = profile
        }
        val threshold = profile.thresholdDb
        val ratio = profile.ratio
        val targetMakeup = profile.makeupDb

        val encoding = inputAudioFormat.encoding
        val channels = inputAudioFormat.channelCount
        val sampleBytes = inputAudioFormat.bytesPerFrame / channels
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        val output = replaceOutputBuffer(end - start)

        var ms = meanSquare
        var reduction = gainReductionDb
        var makeup = makeupDb

        var frameStart = start
        while (frameStart < end) {
            var sumSquares = 0f
            for (ch in 0 until channels) {
                val s = inputBuffer.readSample(frameStart + ch * sampleBytes, encoding)
                sumSquares += s * s
            }
            ms = detectorCoef * ms + (1f - detectorCoef) * (sumSquares / channels)
            val levelDb = 10f * log10(ms + POWER_FLOOR)

            val targetReduction = staticCurveDb(levelDb, threshold, ratio) - levelDb
            val coef = if (targetReduction < reduction) attackCoef else releaseCoef
            reduction = coef * reduction + (1f - coef) * targetReduction

            makeup = smoothingCoef * makeup + (1f - smoothingCoef) * targetMakeup
            val gain = 10f.pow((reduction + makeup) / 20f)
            for (ch in 0 until channels) {
                val s = inputBuffer.readSample(frameStart + ch * sampleBytes, encoding)
                output.writeSample(s * gain, encoding)
            }
            frameStart += inputAudioFormat.bytesPerFrame
        }

        meanSquare = ms
        gainReductionDb = reduction
        makeupDb = makeup
        inputBuffer.position(end)
        output.flip()
    }

    override fun onFlush() {
        meanSquare = 0f
        gainReductionDb = 0f
        makeupDb = liveProfile.current.makeupDb
    }

    companion object {
        private const val RMS_WINDOW_SECONDS = 0.010f
        private const val KNEE_WIDTH_DB = 6f
        private const val POWER_FLOOR = 1e-12f

        fun staticCurveDb(x: Float, threshold: Float, ratio: Float): Float {
            val overshoot = x - threshold
            return when {
                2f * overshoot < -KNEE_WIDTH_DB -> x
                2f * overshoot > KNEE_WIDTH_DB -> threshold + overshoot / ratio
                else -> {
                    val k = overshoot + KNEE_WIDTH_DB / 2f
                    x + (1f / ratio - 1f) * k * k / (2f * KNEE_WIDTH_DB)
                }
            }
        }
    }
}
