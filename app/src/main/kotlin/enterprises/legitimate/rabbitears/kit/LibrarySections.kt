package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.LibraryKind

/** A primary-navigation section backed by a library kind. Home is not one: it always shows. */
enum class LibrarySection(val kind: LibraryKind) {
    movies(LibraryKind.movies),
    tv(LibraryKind.tv),
    music(LibraryKind.music),
    youtube(LibraryKind.youtube);

    companion object {
        /** The sections for the server's library kinds; every section while they are unknown. */
        fun visible(kinds: Collection<LibraryKind>?): Set<LibrarySection> =
            if (kinds == null) entries.toSet() else entries.filter { it.kind in kinds }.toSet()

        /** [current] while it is still shown, else null for Home. */
        fun stay(current: LibrarySection?, visible: Set<LibrarySection>) = current?.takeIf {
            it in visible
        }
    }
}
