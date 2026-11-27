package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Edition
import enterprises.legitimate.rabbitears.api.models.WatchState

/**
 * A title's editions as details offers them. A title with one edition, or whose editions are not
 * known, offers no choice and names none, so it plays and resumes as the server decides.
 */
object Editions {
    fun default(editions: List<Edition>): Edition? =
        editions.firstOrNull { it.isDefault } ?: editions.firstOrNull()

    /** The edition the saved position belongs to, else the default. */
    fun preselected(editions: List<Edition>, watch: WatchState?): Edition? =
        editions.firstOrNull { it.id == watch?.editionId } ?: default(editions)

    /** A position that names no edition belongs to the default one. */
    fun resumes(editions: List<Edition>, chosen: Edition?, watch: WatchState?): Boolean {
        if (watch == null || watch.positionMs <= 0) return false
        if (editions.size < 2 || chosen == null) return true
        return (watch.editionId ?: default(editions)?.id) == chosen.id
    }

    /** `Extended · 3h 48m · 4K, 1080p`. */
    fun label(edition: Edition): String =
        listOfNotNull(
                edition.name,
                edition.runtimeMs?.let(Format::minutes),
                edition.copies
                    .mapNotNull { it.quality }
                    .distinct()
                    .joinToString(", ")
                    .ifEmpty { null },
            )
            .joinToString(" · ")

    /** The editionId a play or an add sends. */
    fun requested(editions: List<Edition>, chosen: Edition?): String? =
        chosen?.id?.takeIf { editions.size > 1 }

    /** The file details describes: the chosen edition's first copy, else the item's first file. */
    fun described(editions: List<Edition>, chosen: Edition?): String? =
        chosen?.copies?.firstOrNull()?.mediaFileId?.takeIf { editions.size > 1 }

    fun titled(title: String, editionName: String?): String =
        editionName?.let { "$title · $it" } ?: title
}
