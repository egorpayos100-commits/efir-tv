package plus.efir.tv.data

/**
 * Some playlist addresses carry the time they stop working inside themselves.
 * Reading it is pure string work: nothing is fetched and no source is touched.
 */
object SourceStamps {

    /**
     * Every digit run in the address, because the deadline hides in three shapes:
     * `/600/1792342581/index.m3u8`, `/expires/1792410659037/` and `?k=1792417125p431i91…`,
     * where it runs straight into the rest of the signature.
     */
    private val RUN = Regex("""\d+""")

    /** 2011..2100 in unix seconds, so channel ids and resolutions are not mistaken for a deadline. */
    private const val MIN = 1_300_000_000L
    private const val MAX = 4_100_000_000L

    /**
     * Death time in millis, or null when the address is a plain URL that never expires.
     * The lists are repainted per row on every focus move, so the answer is kept: the same address
     * always carries the same deadline.
     */
    fun expiryOf(url: String): Long? = synchronized(memo) {
        if (memo.containsKey(url)) return memo[url]
        val found = read(url)
        if (memo.size >= MEMO_MAX) memo.clear()
        memo[url] = found
        found
    }

    fun isExpired(url: String, now: Long = System.currentTimeMillis()): Boolean {
        val expiry = expiryOf(url) ?: return false
        return expiry <= now
    }

    private fun read(url: String): Long? =
        RUN.findAll(url)
            .map { it.value }
            .filter { it.length == 10 || it.length == 13 }
            .mapNotNull { it.toLongOrNull() }
            .map { if (it < 100_000_000_000L) it * 1000 else it }
            .filter { it / 1000 in MIN..MAX }
            .maxOrNull()

    private const val MEMO_MAX = 4_096
    private val memo = HashMap<String, Long?>()
}
