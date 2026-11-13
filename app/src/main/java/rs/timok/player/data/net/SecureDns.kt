package rs.timok.player.data.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import rs.timok.player.data.settings.AppPrefs
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves host names over HTTPS (Cloudflare, then Google) while [AppPrefs.secureDns] is on. Some ISPs block
 * the CDN hosts IPTV panels redirect streams to by answering DNS with their own block page. Falls back to the
 * system resolver when DoH is unreachable, and always uses it for IP literals and local names.
 */
class SecureDns : Dns {

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val resolvers: List<Dns> by lazy {
        // Plain client: DoH lookups must not go through this resolver again.
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        listOf(
            doh(bootstrap, "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh(bootstrap, "https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (!AppPrefs.secureDns.value || !isPublicName(hostname)) return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        for (resolver in resolvers) {
            val addresses = runCatching { resolver.lookup(hostname) }.getOrNull()
            if (!addresses.isNullOrEmpty()) {
                cache[hostname] = Entry(addresses, now + CACHE_MS)
                return addresses
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    /** Forgets cached answers, e.g. after the setting changes. */
    fun clear() = cache.clear()

    private fun doh(client: OkHttpClient, url: String, vararg bootstrapIps: String) = DnsOverHttps.Builder()
        .client(client)
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(bootstrapIps.map { InetAddress.getByName(it) })
        .build()

    private fun isPublicName(host: String) =
        '.' in host && !host.endsWith(".local") && !host.endsWith(".lan") && !IP_LITERAL.matches(host)

    private companion object {
        const val CACHE_MS = 5 * 60_000L
        val IP_LITERAL = Regex("^[0-9.]+$|:")
    }
}
