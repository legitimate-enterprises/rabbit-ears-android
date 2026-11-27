package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.ActiveOccasion
import enterprises.legitimate.rabbitears.api.models.Item
import java.time.LocalDate

/** An occasion in season and the titles the profile may see in it. */
class OccasionShelf(val occasion: ActiveOccasion, val items: List<Item>) {
    /** What titles the row on Home: the server's playful phrase, else the occasion's name. */
    val title: String
        get() = occasion.headline?.takeIf { it.isNotBlank() } ?: occasion.name
}

/**
 * The three colours of an occasion's wash as opaque ARGB, or null unless there are exactly three
 * `#RRGGBB` ones, in which case the screen keeps its plain background.
 */
object OccasionWash {
    private val hex = Regex("^#[0-9A-Fa-f]{6}$")

    fun palette(colors: List<String>?): List<Int>? =
        colors
            ?.takeIf { it.size == 3 && it.all(hex::matches) }
            ?.map { 0xFF000000.toInt() or it.drop(1).toInt(16) }
}

/**
 * Home's occasion shelves: what the server says is in season on the device's calendar date, nearest
 * first. The server decides which titles each holds; nothing here filters them.
 */
class OccasionsModel(
    private val api: Api,
    private val profileId: Long,
    private val today: () -> LocalDate = LocalDate::now,
) {
    var shelves by mutableStateOf<List<OccasionShelf>>(emptyList())
        private set

    /**
     * Reads the shelves again. Home never breaks over this: a failed read keeps the shelves shown
     * and says nothing, and a title list that fails drops only its own occasion.
     */
    suspend fun load() {
        val active =
            runCatching { api.call { profiles.listActiveOccasions(profileId, today()) }.items }
                .getOrNull() ?: return
        shelves = active.mapNotNull { shelf(it) }
    }

    /** Every title the profile may see in an occasion, for its own screen; null if unreadable. */
    suspend fun members(occasionId: Long): List<Item>? = runCatching {
        pages { offset ->
            api.call {
                    items.listItems(occasionId = occasionId, limit = PAGE, offset = offset ?: 0)
                }
                .let { it.items to it.nextOffset }
        }
    }
        .getOrNull()

    /** A details page's rows: each occasion in season [itemId] is in, with its other titles. */
    suspend fun containing(itemId: Long): List<OccasionShelf> {
        val active =
            runCatching {
                api.call { profiles.listActiveOccasions(profileId, today(), itemId) }.items
            }
                .getOrNull() ?: return emptyList()
        return active.mapNotNull { shelf(it, excluding = itemId) }
    }

    private suspend fun shelf(occasion: ActiveOccasion, excluding: Long? = null): OccasionShelf? =
        runCatching {
            // One more than a row shows, so leaving the title out still fills it.
            api.call {
                    items.listItems(
                        occasionId = occasion.id,
                        limit = SHELF + if (excluding == null) 0 else 1,
                    )
                }
                .items
        }
        .getOrNull()
        ?.filter { it.id != excluding }
        ?.take(SHELF)
        ?.takeIf { it.isNotEmpty() }
        ?.let { OccasionShelf(occasion, it) }

    private companion object {
        /** As many as Home's Recently added. */
        const val SHELF = 20
        const val PAGE = 200
    }
}
