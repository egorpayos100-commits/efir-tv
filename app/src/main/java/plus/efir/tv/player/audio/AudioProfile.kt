package plus.efir.tv.player.audio

/**
 * Режимы выравнивания звука и компрессии:
 * - NORMAL (Стандарт): без компрессии (0 dB поправки, ratio 1:1, лимитер выключен).
 * - SOFT (Мягкий): выравнивание громкости, усиление речи +3 dB, мягкая компрессия взрывов 3:1, подъем +4 dB.
 * - NIGHT (Ночной): максимальная четкость диалогов +6 dB, сильная компрессия эффектов 6:1, подъем +8 dB.
 */
enum class AudioProfile(
    val title: String,
    val dialogGainDb: Float,
    val thresholdDb: Float,
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val makeupDb: Float,
    val limiterEnabled: Boolean,
    val loudnessGainMb: Int,
) {
    NORMAL(
        title = "Стандарт",
        dialogGainDb = 0f,
        thresholdDb = 0f,
        ratio = 1f,
        attackMs = 10f,
        releaseMs = 200f,
        makeupDb = 0f,
        limiterEnabled = false,
        loudnessGainMb = 0,
    ),
    SOFT(
        title = "Мягкий",
        dialogGainDb = 3f,
        thresholdDb = -24f,
        ratio = 3f,
        attackMs = 10f,
        releaseMs = 200f,
        makeupDb = 4f,
        limiterEnabled = true,
        loudnessGainMb = 600,
    ),
    NIGHT(
        title = "Ночной",
        dialogGainDb = 6f,
        thresholdDb = -30f,
        ratio = 6f,
        attackMs = 5f,
        releaseMs = 300f,
        makeupDb = 8f,
        limiterEnabled = true,
        loudnessGainMb = 1200,
    );

    fun next(): AudioProfile = entries[(ordinal + 1) % entries.size]

    companion object {
        const val LIMITER_CEILING_DB = -1f

        fun fromName(name: String?): AudioProfile =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SOFT
    }
}

class LiveAudioProfile(initial: AudioProfile = AudioProfile.SOFT) {
    @Volatile
    var current: AudioProfile = initial

    @Volatile
    var filmAudioProcessing: Boolean = false
}
