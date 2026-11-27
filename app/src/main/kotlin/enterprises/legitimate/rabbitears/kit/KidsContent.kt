package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which list-managed kids allow one movie, show or channel on their manual allow lists, and the
 * toggle for each. The server answers only for the kids of the device's household.
 */
class KidAllowList(private val api: Api, private val itemId: String) {
    var allowed by mutableStateOf<Set<String>>(emptySet())
        private set

    var error by mutableStateOf<String?>(null)
        private set

    suspend fun load() {
        try {
            allowed = api.call { profiles.listAllowedFor(itemId) }.profileIds.toSet()
            error = null
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }

    suspend fun toggle(kidId: String) {
        val allow = kidId !in allowed
        try {
            api.call {
                if (allow) profiles.allowItem(kidId, itemId)
                else profiles.disallowItem(kidId, itemId)
            }
            allowed = if (allow) allowed + kidId else allowed - kidId
            error = null
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
        }
    }
}

/** The server's profile colors, the only ones it accepts. */
object ProfileColors {
    val PALETTE =
        listOf(
            "#3C4170",
            "#2E6B5B",
            "#6B3A2E",
            "#5B4A8A",
            "#8A5A2B",
            "#2E5B8A",
            "#6B2E5A",
            "#4A6B2E",
        )

    /** The opaque ARGB of a `#RRGGBB` color; the first palette color when it is absent or bad. */
    fun argb(hex: String?): Long =
        0xFF000000 or
            (hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
                ?: PALETTE[0].removePrefix("#").toLong(16))
}
