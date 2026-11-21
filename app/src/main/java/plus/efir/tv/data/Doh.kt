package plus.efir.tv.data

import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import org.json.JSONObject

/**
 * The provider filter answers DNS for our site with its own address and serves a block page.
 * Our hosts are dialled by known edge address first, so the router's answer is never asked for;
 * DNS-over-HTTPS, reached by IP literal, is only there for the day those addresses move. Both
 * paths keep the real hostname for SNI and the certificate check. Any other host uses the system
 * DNS untouched.
 */
object Doh {
    private const val TAG = "Doh"
    private const val SUFFIX = ".qoder.website"

    /** Origin edges of the site, verified from the box's own network. */
    private val EDGES = listOf("47.75.109.243", "47.57.8.21")
    private const val CACHE_MS = 30 * 60 * 1000L
    private const val RESOLVE_TIMEOUT_MS = 5000
    private val RESOLVERS = listOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query",
        "https://8.8.8.8/resolve",
    )
    private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * Hosts the provider's DNS is known to poison. GitHub raw is where playlists, the version
     * file and APK updates live; the system resolver on some boxes answers it with a block page,
     * so DoH is used for it the same way it is used for the site's own domain.
     */
    private val FORCED_DOH = setOf(
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
    )

    fun appliesTo(host: String): Boolean =
        host.lowercase().endsWith(SUFFIX) || host.lowercase() in FORCED_DOH

    /** Addresses to dial for the host, best first; null would mean "ask the system". */
    fun dial(host: String): List<String?> =
        if (host.lowercase().endsWith(SUFFIX)) EDGES + listOf(resolve(host))
        else if (host.lowercase() in FORCED_DOH) listOf(resolve(host))
        else listOf(null)

    /**
     * Unit address for any host, asked over DNS-over-HTTPS. Artwork hosts are not ours, but the
     * provider's filter can poison them just the same, and a picture that cannot be resolved is a
     * grey monogram on the shelf.
     */
    internal fun addressOf(host: String): String? = resolve(host)

    /** Pins the connection to one address, keeping the hostname for SNI and the certificate. */
    fun pin(connection: HttpURLConnection, ip: String?) {
        if (ip == null || connection !is HttpsURLConnection) return
        connection.sslSocketFactory = PinnedSocketFactory(ip, connection.connectTimeout)
        Log.i(TAG, "pinned ${connection.url.host} to $ip")
    }

    private fun resolve(host: String): String? {
        cache[host]?.let { (at, ip) ->
            if (System.currentTimeMillis() - at < CACHE_MS) return ip
        }
        for (resolver in RESOLVERS) {
            val ip = query(resolver, host)
            if (ip != null) {
                cache[host] = System.currentTimeMillis() to ip
                return ip
            }
        }
        return null
    }

    private fun query(resolver: String, host: String): String? {
        val url = URL("$resolver?name=$host&type=A")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = RESOLVE_TIMEOUT_MS
            connection.readTimeout = RESOLVE_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/dns-json")
            connection.setRequestProperty("User-Agent", "efir-tv")
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().readText()
            val answers = JSONObject(text).optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                if (answer.optInt("type") == 1) {
                    val data = answer.optString("data")
                    if (IPV4.matches(data)) return data
                }
            }
            null
        } catch (error: Exception) {
            Log.w(TAG, "DoH query failed via $resolver", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private class PinnedSocketFactory(
        private val ip: String,
        private val connectTimeoutMs: Int,
    ) : SSLSocketFactory() {
        private val delegate = getDefault() as SSLSocketFactory

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(host: String, port: Int): Socket = open(null, host, port)

        override fun createSocket(address: InetAddress, port: Int): Socket =
            open(null, address.hostName ?: address.hostAddress ?: "", port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = open(null, address.hostName ?: address.hostAddress ?: "", port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = open(null, host, port)

        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            open(socket, host, port)

        private fun open(ignored: Socket?, host: String, port: Int): Socket {
            // HttpsURLConnection has already dialled the poisoned answer; drop that socket and
            // dial the DoH one, then wrap it with TLS under the real hostname.
            runCatching { ignored?.close() }
            val plain = Socket()
            plain.connect(InetSocketAddress(ip, port), connectTimeoutMs)
            return delegate.createSocket(plain, host, port, true)
        }
    }
}
