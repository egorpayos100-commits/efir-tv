package plus.efir.tv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Уровень громкости фильмов. Фильмы приходят с разной громкостью: один приходится слушать на
 * максимуме, другой орёт. Обработчик меряет громкость по K-взвешиванию (как EBU R128) и плавно
 * подводит её к уровню трейлера, который он назвал образцом, а лимитер не даёт пикам захрипеть
 * после подъёма. Строка плейлиста может добавить свою поправку `tvg-gain` — она ложится поверх
 * автоматики. Эфир обработчик не трогает: для него он просто пропускает звук как есть.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class GainProcessor : BaseAudioProcessor() {

    /** Фильмы — да, эфир — нет: выключенный обработчик переписывает звук без изменений. */
    @Volatile
    var enabled: Boolean = false

    /** Ручная поправка текущей строки из плейлиста, децибелы. */
    @Volatile
    var trimDb: Float = 0f
        set(value) {
            field = value.coerceIn(-24f, 24f)
        }

    private var channels = 2
    private var blockFrames = 19_200
    private var release = 0.9997f
    private var rampStep = 0.0001f

    /** Коэффициенты K-взвешивания (BS.1770) и состояние фильтров на каждый канал. */
    private val k1 = FloatArray(3)
    private val k2 = FloatArray(3)
    private val s1 = FloatArray(3)
    private val s2 = FloatArray(3)
    private var z1 = FloatArray(0)
    private var z2 = FloatArray(0)

    private var blockSum = FloatArray(0)
    private var blockCount = 0
    private var blockPower = FloatArray(HISTORY).also { it.fill(Float.NaN) }
    private var historyIndex = 0
    private var historyFilled = 0

    /** Автоматическая поправка, децибелы: подводится к цели не спеша, чтобы не «дышать». */
    private var autoDb = 0f
    private var applied = 1f
    private var envelope = 0f
    private var sinceBlock = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!enabled || inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount.coerceIn(1, 8)
        val rate = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        blockFrames = (rate * BLOCK_S).toInt().coerceAtLeast(1024)
        release = 1f - 1f / (RELEASE_S * rate)
        rampStep = 1f / (RAMP_S * rate)
        z1 = FloatArray(channels * 2)
        z2 = FloatArray(channels * 2)
        blockSum = FloatArray(channels)
        sinceBlock = 0
        weighting(rate)
        restart()
        return inputAudioFormat
    }

    override fun onFlush() {
        restart()
    }

    private fun restart() {
        z1.fill(0f)
        z2.fill(0f)
        blockSum.fill(0f)
        blockCount = 0
        sinceBlock = 0
        envelope = 0f
        autoDb = 0f
        applied = gainOf(trimDb)
        blockPower.fill(Float.NaN)
        historyIndex = 0
        historyFilled = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        inputBuffer.order(ByteOrder.nativeOrder())

        val frameBytes = inputAudioFormat.bytesPerFrame
        if (frameBytes <= 0) {
            return
        }

        val out = replaceOutputBuffer(inputBuffer.remaining())
        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val ceiling = CEILING * Short.MAX_VALUE
        val frame = IntArray(channels)

        while (inputBuffer.remaining() >= frameBytes) {
            var peak = 0
            for (c in 0 until channels) {
                val v = inputBuffer.short.toInt()
                frame[c] = v
                peak = max(peak, abs(v))
                val weighted = weight(c, v.toFloat() / Short.MAX_VALUE)
                blockSum[c] += weighted * weighted
            }
            sinceBlock += 1
            if (sinceBlock >= blockFrames) closeBlock()

            envelope = max(peak.toFloat(), envelope * release)

            // Поправка меняется только по границе блока, поэтому внутри неё звук ровный.
            val wanted = gainOf(trimDb + autoDb)
            if (applied < wanted) applied = min(wanted, applied + rampStep)
            else if (applied > wanted) applied = max(wanted, applied - rampStep)

            val gain = if (envelope * applied > ceiling) ceiling / envelope else applied
            for (c in 0 until channels) {
                out.putShort((frame[c] * gain).toInt().coerceIn(-32768, 32767).toShort())
            }
        }

        out.flip()
    }

    /** 400 мс громкости: короткое окно реагирует на смену программы, история — сглаживает. */
    private fun closeBlock() {
        var power = 0.0
        for (c in 0 until channels) {
            val mean = blockSum[c] / sinceBlock
            power += mean.toDouble()
            blockSum[c] = 0f
        }
        sinceBlock = 0
        val loudness = if (power > 1e-12) -0.691 + 10 * log10(power) else -120.0
        blockPower[historyIndex] = loudness.toFloat()
        historyIndex = (historyIndex + 1) % HISTORY
        if (historyFilled < HISTORY) historyFilled += 1

        if (historyFilled < WARMUP_BLOCKS) return

        // Среднее по короткому окну в мощности: тишину не учитываем, иначе поправка улетает.
        var sum = 0.0
        var count = 0
        for (value in blockPower) {
            if (value.isNaN() || value < SILENCE_LUFS) continue
            sum += 10.0.pow(value / 10.0)
            count += 1
        }
        if (count == 0) return

        val shortTerm = 10 * log10(sum / count)
        // Подъём поверх образца: 20·log10(1.15) ≈ 1.2 дБ, то есть на 15 % громче трейлера.
        val desired = (TARGET_LUFS + LIFT_DB - shortTerm).toFloat().coerceIn(-MAX_AUTO_DB, MAX_AUTO_DB)
        // Подходим к цели не быстрее STEP_DB за блок: иначе слышно, как звук «подтягивается».
        autoDb += (desired - autoDb).coerceIn(-STEP_DB, STEP_DB)
        autoDb = autoDb.coerceIn(-MAX_AUTO_DB, MAX_AUTO_DB)
    }

    private fun weight(channel: Int, x: Float): Float {
        val i = channel * 2
        var y = k1[0] * x + z1[i]
        z1[i] = k1[1] * x - s1[1] * y + z1[i + 1]
        z1[i + 1] = k1[2] * x - s1[2] * y
        val mid = y
        y = k2[0] * mid + z2[i]
        z2[i] = k2[1] * mid - s2[1] * y + z2[i + 1]
        z2[i + 1] = k2[2] * mid - s2[2] * y
        return y
    }

    /** K-взвешивание EBU R128: верхняя полка плюс обрезка низа, посчитанные под частоту потока. */
    private fun weighting(rate: Float) {
        // Полка около +4 dB на верхах.
        run {
            val a = 10.0.pow(SHELF_GAIN_DB / 40.0)
            val w0 = 2.0 * Math.PI * SHELF_HZ / rate
            val cos = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * SHELF_Q)
            val sq = Math.sqrt(a)
            val b0 = a * ((a + 1) + (a - 1) * cos + 2 * sq * alpha)
            val b1 = -2 * a * ((a - 1) + (a + 1) * cos)
            val b2 = a * ((a + 1) + (a - 1) * cos - 2 * sq * alpha)
            val a0 = (a + 1) - (a - 1) * cos + 2 * sq * alpha
            val a1 = 2 * ((a - 1) - (a + 1) * cos)
            val a2 = (a + 1) - (a - 1) * cos - 2 * sq * alpha
            k1[0] = (b0 / a0).toFloat()
            k1[1] = (b1 / a0).toFloat()
            k1[2] = (b2 / a0).toFloat()
            s1[1] = (a1 / a0).toFloat()
            s1[2] = (a2 / a0).toFloat()
        }
        // Обрезка ниже 38 Гц.
        run {
            val w0 = 2.0 * Math.PI * HIGH_HZ / rate
            val cos = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * HIGH_Q)
            val a0 = 1.0 + alpha
            k2[0] = (1.0 / a0).toFloat()
            k2[1] = (-2.0 / a0).toFloat()
            k2[2] = (1.0 / a0).toFloat()
            s2[1] = (-2.0 * cos / a0).toFloat()
            s2[2] = ((1.0 - alpha) / a0).toFloat()
        }
    }

    private fun gainOf(db: Float): Float = 10f.pow(db / 20f)

    private companion object {
        /** Замеренный уровень его трейлера-образца: −16.4 LUFS, пик −0.7 dBFS. */
        const val TARGET_LUFS = -16.4

        /** Просьба 19.11: фильмы на 15 % громче образца, это +1.2 дБ. */
        const val LIFT_DB = 1.2f

        /** Потолок −1 dBFS: после подъёма тихого фильма в звуке не остаётся клиппинга. */
        const val CEILING = 0.891f

        const val BLOCK_S = 0.4f
        const val HISTORY = 8
        const val WARMUP_BLOCKS = 5
        const val SILENCE_LUFS = -50f

        /** Насколько вообще можно уйти от исходной громкости. */
        const val MAX_AUTO_DB = 18f

        /** Скорость подхода к цели: 0.5 дБ за 400 мс — около децибела с четвертью в секунду. */
        const val STEP_DB = 0.5f

        const val RELEASE_S = 0.15f
        const val RAMP_S = 0.20f

        /** Коэффициенты K-взвешивания ITU-R BS.1770. */
        const val SHELF_GAIN_DB = 3.999843853973347
        const val SHELF_HZ = 1681.974450955533
        const val SHELF_Q = 0.7071752369554196
        const val HIGH_HZ = 38.13547087602444
        const val HIGH_Q = 0.5003270373238773
    }
}
