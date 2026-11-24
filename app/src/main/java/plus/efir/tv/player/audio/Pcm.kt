package plus.efir.tv.player.audio

import androidx.media3.common.C
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.pow

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun isSupportedEncoding(encoding: Int): Boolean =
    encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun encodingName(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_16BIT -> "PCM_16BIT"
    C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
    else -> "other ($encoding)"
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Suppress("NOTHING_TO_INLINE")
internal inline fun ByteBuffer.readSample(byteIndex: Int, encoding: Int): Float =
    if (encoding == C.ENCODING_PCM_FLOAT) getFloat(byteIndex) else getShort(byteIndex) / 32768f

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Suppress("NOTHING_TO_INLINE")
internal inline fun ByteBuffer.writeSample(value: Float, encoding: Int) {
    if (encoding == C.ENCODING_PCM_FLOAT) {
        putFloat(value)
    } else {
        putShort((value * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
    }
}

internal fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

internal fun onePoleCoefficient(timeSeconds: Float, sampleRate: Int): Float =
    if (timeSeconds <= 0f) 0f else exp(-1.0 / (timeSeconds * sampleRate)).toFloat()

internal const val PARAMETER_SMOOTHING_SECONDS = 0.050f / 3f
