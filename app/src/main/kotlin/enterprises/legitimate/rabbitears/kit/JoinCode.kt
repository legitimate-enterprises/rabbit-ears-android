package enterprises.legitimate.rabbitears.kit

import java.net.URI

/** Join codes as the server issues them: `XXXX-XXXX` from 32 symbols without 0, O, 1 or I. */
object JoinCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** The canonical code for what was typed, ignoring case, spaces and dashes. */
    fun normalize(text: String): String? {
        val symbols = text.uppercase().filter { !it.isWhitespace() && it != '-' }
        if (symbols.length != 8 || !symbols.all { it in ALPHABET }) return null
        return "${symbols.take(4)}-${symbols.takeLast(4)}"
    }

    /** The server and code of a join link such as `https://host/join/7KQ4-MNP2`. */
    fun parseLink(text: String): Pair<ServerEndpoint, String>? {
        if ("://" !in text) return null
        val parts = runCatching { URI(text.trim()).path }.getOrNull()?.split('/') ?: return null
        if (parts.size != 3 || parts[1] != "join") return null
        val code = normalize(parts[2]) ?: return null
        return (ServerEndpoint.parse(text) ?: return null) to code
    }
}
