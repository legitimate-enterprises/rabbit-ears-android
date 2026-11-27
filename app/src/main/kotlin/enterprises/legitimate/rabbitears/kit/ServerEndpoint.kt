package enterprises.legitimate.rabbitears.kit

import java.net.URI

/** Where the Rabbit Ears server is reached: scheme, host and port, no path. */
data class ServerEndpoint(val base: String) {
    /** The generated client's base path. */
    val api: String
        get() = "$base/api/v2"

    /** A server-relative path from the API, such as a subtitle sidecar, as an absolute URL. */
    fun resolve(path: String): String = URI(base + "/").resolve(path).toString()

    /**
     * What the last local check found. Shared by whatever holds this instance, and no part of the
     * endpoint's identity.
     */
    val home = HomeLink()

    /**
     * Whether this device is on the server's own network, by the address signed in at or by a local
     * check. The playback snapshot reports it as discovered locally.
     */
    val atHome: Boolean
        get() = isLocalNetwork || home.localBase != null

    /** A stream's path as an absolute URL, at the server's own address once a check found one. */
    fun resolveStream(path: String): String =
        URI((home.stream() ?: base) + "/").resolve(path).toString()

    /**
     * Runs a local check through [api], which is signed in, unless the address is already local or,
     * when not [force]d, the last check still stands: made on [path] within 10 minutes, whatever it
     * found. A check that fails for any reason means away from home.
     */
    suspend fun confirmHome(
        api: Api,
        path: PathFacts,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (isLocalNetwork || (!force && home.stands(path, nowMs))) return
        home.refresh(api, path, nowMs)
    }

    /** What the Server field shows: the bare host when [parse] reads it back as this address. */
    val typed: String
        get() =
            base.removePrefix("http://").removeSuffix(":$DEFAULT_PORT").takeIf { parse(it) == this }
                ?: base

    /**
     * Whether only a local network reaches the address: localhost, mDNS `.local`, RFC 1918,
     * link-local or IPv6 unique local. A server found by NSD is local whatever this says.
     */
    val isLocalNetwork: Boolean
        get() {
            val host = URI(base).host.lowercase().removePrefix("[").removeSuffix("]")
            if (host == "localhost" || host.endsWith(".local")) return true
            if (':' in host)
                return host == "::1" ||
                    host.startsWith("fc") ||
                    host.startsWith("fd") ||
                    host.startsWith("fe80")
            val octets = host.split('.').map { it.toIntOrNull() ?: return false }
            if (octets.size != 4) return false
            val (a, b) = octets
            return a == 10 ||
                a == 127 ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31)
        }

    /**
     * Whether a password or token may be sent here: plain http is for a server on the local
     * network; anywhere else it would cross the internet in the clear.
     */
    val isSecure: Boolean
        get() = base.startsWith("https://") || isLocalNetwork

    companion object {
        const val DEFAULT_PORT = 8477

        /**
         * Reads what a person types in the Server field: a host, host and port, or any URL on the
         * server. A bare host gets http and the default port; a URL keeps its scheme and port.
         */
        fun parse(input: String): ServerEndpoint? {
            val text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            val hasScheme = "://" in text
            val uri =
                runCatching { URI(if (hasScheme) text else "http://$text") }.getOrNull()
                    ?: return null
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (scheme !in setOf("http", "https") || host.isNullOrEmpty()) return null
            val port = if (uri.port == -1 && !hasScheme) DEFAULT_PORT else uri.port
            return ServerEndpoint(if (port == -1) "$scheme://$host" else "$scheme://$host:$port")
        }

        /** A server NSD resolved, which reports a bare host and port. */
        fun of(host: String, port: Int): ServerEndpoint =
            ServerEndpoint("http://${if (':' in host) "[$host]" else host}:$port")
    }
}
