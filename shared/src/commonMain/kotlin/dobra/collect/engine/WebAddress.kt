package dobra.collect.engine

/**
 * An `http` or `https` address, read without the platform's URL classes (common code). Addresses with user
 * information (`user@host`), IP-literal hosts in brackets or other schemes are not accepted.
 */
data class WebAddress(val scheme: String, val host: String, val port: Int?, val rest: String) {
    /** `scheme://host[:port]` */
    val origin: String get() = "$scheme://$host" + (port?.let { ":$it" } ?: "")

    override fun toString(): String = origin + rest

    /** Resolves [ref] (absolute, `//host/...`, `/path`, `?query` or a relative path) against this address. */
    fun resolve(ref: String): WebAddress? {
        val r = ref.trim()
        return when {
            SCHEME.containsMatchIn(r) -> parse(r)
            r.startsWith("//") -> parse("$scheme:$r")
            r.startsWith("/") -> parse(origin + r)
            r.startsWith("?") -> parse(origin + rest.substringBefore('#').substringBefore('?') + r)
            r.startsWith("#") || r.isEmpty() -> parse(origin + rest.substringBefore('#') + r)
            else -> {
                val path = rest.substringBefore('#').substringBefore('?')
                val dir = path.substring(0, path.lastIndexOf('/') + 1).ifEmpty { "/" }
                parse(origin + dir + r)
            }
        }
    }

    companion object {
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

        fun parse(address: String): WebAddress? {
            val s = address.trim()
            val colon = s.indexOf("://")
            if (colon <= 0) return null
            val scheme = s.substring(0, colon).lowercase()
            if (scheme != "http" && scheme != "https") return null
            val afterScheme = s.substring(colon + 3)
            val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) afterScheme.length else it }
            val authority = afterScheme.substring(0, authorityEnd)
            if (authority.isEmpty() || '@' in authority || '[' in authority || '\\' in authority) return null
            val hostPart = authority.substringBefore(':').lowercase().removeSuffix(".")
            val portPart = if (':' in authority) authority.substringAfter(':') else null
            if (!HOST.matches(hostPart)) return null
            val port = when {
                portPart == null || portPart.isEmpty() -> null
                portPart.all { it.isDigit() } && portPart.length <= 5 -> portPart.toInt().takeIf { it in 1..65535 }
                    ?: return null
                else -> return null
            }
            val rest = afterScheme.substring(authorityEnd).let { if (it.isEmpty() || it[0] != '/') "/$it" else it }
            return WebAddress(scheme, hostPart, port, rest)
        }
    }
}

/**
 * Where a collector may fetch: the host of its source address and that host's subdomains, with a leading `www.`
 * of the source ignored (source `https://www.example.org/` allows `example.org` and `api.example.org`, not
 * `example.net`). Any port of an allowed host, `http` or `https`.
 */
class SourceDomain(val source: WebAddress) {
    val domain: String = source.host.removePrefix("www.")

    fun allows(address: WebAddress): Boolean = address.host == domain || address.host.endsWith(".$domain")
}
