package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.Edition
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.Playlist
import enterprises.legitimate.rabbitears.api.models.PlaylistAccess
import enterprises.legitimate.rabbitears.api.models.PlaylistEntriesInput
import enterprises.legitimate.rabbitears.api.models.PlaylistEntry
import enterprises.legitimate.rabbitears.api.models.PlaylistEntryInput
import enterprises.legitimate.rabbitears.api.models.PlaylistEntryKind
import enterprises.legitimate.rabbitears.api.models.PlaylistInput
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.PlaylistPlayOrderItem
import kotlin.random.Random

/**
 * The rest of a pass through a playlist's video rows: [rows] in play order and the [index] now
 * playing. The server expanded every container, so nothing here does. Shuffle's run has no
 * [playlistId], so its Up next is its own next row and never the server's.
 */
data class PlaylistRun(
    val playlistId: String?,
    val rows: List<PlaylistPlayOrderItem>,
    val index: Int = 0,
) {
    val itemId: String
        get() = rows[index].itemId

    val editionId: String?
        get() = rows[index].editionId

    val editionName: String?
        get() = rows[index].editionName

    fun next(): PlaylistRun? = if (index + 1 < rows.size) copy(index = index + 1) else null

    /** Up next as the row at [index]. */
    val upNext: UpNextPlay
        get() =
            rows[index].let {
                UpNextPlay(
                    it.item?.let(Catalog::title) ?: it.song?.title ?: "",
                    it.itemId,
                    this,
                    it.editionId,
                )
            }

    companion object {
        /**
         * Shuffle over [titles], movies or episodes: one uniformly random order of them all, each
         * in its default edition; null for none.
         */
        fun shuffled(titles: List<Item>, random: Random = Random): PlaylistRun? =
            titles
                .takeIf { it.isNotEmpty() }
                ?.shuffled(random)
                ?.map {
                    val kind =
                        if (it.kind == ItemKind.episode) PlaylistEntryKind.episode
                        else PlaylistEntryKind.movie
                    PlaylistPlayOrderItem(it.id, it.id, kind, item = it)
                }
                ?.let { PlaylistRun(null, it) }
    }
}

/** The entry's name: a season is named with its show, which alone does not say which. */
val PlaylistEntry.title: String
    get() =
        when (kind) {
            PlaylistEntryKind.artist -> artist?.name
            PlaylistEntryKind.album -> album?.title
            PlaylistEntryKind.song -> song?.title
            PlaylistEntryKind.season ->
                listOfNotNull(show, item).joinToString(" · ") { Catalog.title(it) }
            PlaylistEntryKind.collection -> collection?.name
            else -> item?.let { Editions.titled(Catalog.title(it), editionName) }
        }.orEmpty()

/** What the entry stands for now: its episodes or songs, or the one thing it is. */
val PlaylistEntry.caption: String
    get() =
        when (kind) {
            PlaylistEntryKind.show,
            PlaylistEntryKind.season -> Format.plural(itemCount, "episode")
            PlaylistEntryKind.artist,
            PlaylistEntryKind.album -> Format.plural(itemCount, "song")
            PlaylistEntryKind.song ->
                listOfNotNull(song?.artistName, song?.durationMs?.let(Format::length))
                    .joinToString(" · ")
            PlaylistEntryKind.episode ->
                listOfNotNull(show?.let(Catalog::title), item?.let(Format::episodeCode))
                    .joinToString(" · ")
            PlaylistEntryKind.collection -> Format.plural(itemCount, "movie")
            PlaylistEntryKind.movie ->
                item?.metadata?.runtimeMinutes?.let(Format::runtime).orEmpty()
        }

/**
 * The item whose artwork stands for the entry, a song's being its album's; none for a collection.
 */
val PlaylistEntry.artId: String?
    get() = song?.albumId ?: itemId

/** The address of the entry's art that the object its kind names carries. */
val PlaylistEntry.artUrl: String?
    get() =
        item?.posterUrl
            ?: artist?.posterUrl
            ?: album?.posterUrl
            ?: song?.posterUrl
            ?: collection?.posterUrl

/** Whether the profile owns the playlist, and so alone renames, deletes and shares it. */
val Playlist.isOwned: Boolean
    get() = access == PlaylistAccess.owner

/** Whether rules choose its entries; a copy stored without the flag is not smart. */
val Playlist.isSmart: Boolean
    get() = smart == true

/** Whether the profile may add, remove and reorder its entries; a smart one's follow its rules. */
val Playlist.isEditable: Boolean
    get() = access != PlaylistAccess.read && !isSmart

/** Whether the profile gets Edit rules: the server takes rules from the owner alone. */
val Playlist.editsRules: Boolean
    get() = isOwned && isSmart

/** Whether it carries the Shared badge; a copy stored without the flag is not shared. */
val Playlist.isShared: Boolean
    get() = shared == true

/** `Shared by Ana` on a playlist someone else owns. */
val Playlist.sharedBy: String?
    get() = ownerName?.takeUnless { isOwned }?.let { "Shared by $it" }

/**
 * The active profile's playlists and those shared with it. Each holds one type, movies, tv or
 * music, and its entries take that type's items; kids own none and only read, and the server
 * answers writes beyond a playlist's access with 403.
 */
class PlaylistsModel(
    val api: Api,
    val profileId: String,
    val adult: Boolean = true,
    private val offline: OfflineLibrary? = null,
) {
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    var loaded by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun of(kind: PlaylistKind) = playlists.filter { it.kind == kind }

    /** Whether the profile gets a Playlists view of [kind]: an adult always, a kid once shared. */
    fun offersView(kind: PlaylistKind) = adult || of(kind).isNotEmpty()

    /** The playlists of [kind] an adult may add to: their own and those shared for write. */
    fun editable(kind: PlaylistKind) = if (adult) of(kind).filter { it.isEditable } else emptyList()

    suspend fun load() {
        write {
            playlists =
                offline.answer({ playlists() }) {
                    pages { o ->
                        api.call {
                                playlists.listPlaylists(profileId, limit = PAGE, offset = o ?: 0)
                            }
                            .let { it.items to it.nextOffset }
                    }
                }
        }
        loaded = true
    }

    suspend fun create(name: String, kind: PlaylistKind): Playlist? {
        val trimmed = name(name) ?: return null
        var created: Playlist? = null
        write {
            created =
                api.call { playlists.createPlaylist(profileId, PlaylistInput(trimmed, kind)) }
                    .also { playlists = playlists + it }
        }
        return created
    }

    /**
     * Makes a smart playlist: the playlist, then its rules. A refusal of the rules deletes the
     * playlist again, so none is left without them, and [draft] carries why.
     */
    suspend fun create(draft: SmartDraft): Playlist? {
        val kind = draft.kind
        if (!adult || kind == null || !draft.validate()) return null
        return draft.submit {
            val made = api.call {
                playlists.createPlaylist(profileId, PlaylistInput(name(draft.name)!!, kind))
            }
            try {
                api.call { playlists.replacePlaylistRules(profileId, made.id, draft.rules()) }
            } catch (e: Exception) {
                runCatching { api.call { playlists.deletePlaylist(profileId, made.id) } }
                throw e
            }
            api.call { playlists.getPlaylist(profileId, made.id) }
                .also { playlists = playlists + it }
        }
    }

    /**
     * A smart playlist's rules as a draft to edit, named [name]; null when they could not be read.
     */
    suspend fun draft(playlistId: String, kind: PlaylistKind, name: String): SmartDraft? {
        var draft: SmartDraft? = null
        write {
            val rules = api.call { playlists.getPlaylistRules(profileId, playlistId) }
            draft = SmartDraft(kind, name, rules)
        }
        return draft
    }

    /**
     * Replaces a playlist's rules, renaming a listed one whose name changed. A station's own
     * playlist is listed nowhere, so it takes the rules alone.
     */
    suspend fun saveRules(playlistId: String, draft: SmartDraft): Boolean {
        if (!draft.validate()) return false
        return draft.submit {
            api.call { playlists.replacePlaylistRules(profileId, playlistId, draft.rules()) }
            val listed = playlists.firstOrNull { it.id == playlistId } ?: return@submit
            val trimmed = name(draft.name)
            if (trimmed != null && trimmed != listed.name)
                api.call {
                    playlists.replacePlaylist(
                        profileId,
                        playlistId,
                        PlaylistInput(trimmed, listed.kind),
                    )
                }
            refresh(playlistId)
        } != null
    }

    suspend fun rename(playlist: Playlist, name: String): Boolean {
        val trimmed = name(name) ?: return false
        return write {
            val renamed = api.call {
                playlists.replacePlaylist(
                    profileId,
                    playlist.id,
                    PlaylistInput(trimmed, playlist.kind),
                )
            }
            playlists = playlists.map { if (it.id == renamed.id) renamed else it }
        }
    }

    /** A playlist a station plays is refused with 409 until the station lets it go. */
    suspend fun delete(playlist: Playlist): Boolean =
        try {
            api.call { playlists.deletePlaylist(profileId, playlist.id) }
            playlists = playlists.filter { it.id != playlist.id }
            error = null
            true
        } catch (e: Exception) {
            error =
                if (ApiError.from(e)?.status == 409)
                    "A station plays this playlist. Delete the station or give it another playlist first."
                else AppError.from(e, AppError.SignedOut).message
            false
        }

    /** The entries top to bottom; null when they could not be read. */
    suspend fun entries(playlistId: String): List<PlaylistEntry>? {
        var entries: List<PlaylistEntry>? = null
        write {
            entries =
                offline.answer({ entries(playlistId) }) {
                    pages { o ->
                        api.call {
                                playlists.listPlaylistEntries(
                                    profileId,
                                    playlistId,
                                    limit = PAGE,
                                    offset = o ?: 0,
                                )
                            }
                            .let { it.items to it.nextOffset }
                    }
                }
        }
        return entries
    }

    /** What playing the playlist plays, first to last; null when it could not be read. */
    suspend fun playOrder(playlistId: String): List<PlaylistPlayOrderItem>? {
        var rows: List<PlaylistPlayOrderItem>? = null
        write {
            rows =
                offline.answer({ playOrder(playlistId) }) {
                    pages { o ->
                        api.call {
                                playlists.listPlaylistPlayOrder(
                                    profileId,
                                    playlistId,
                                    limit = PAGE,
                                    offset = o ?: 0,
                                )
                            }
                            .let { it.items to it.nextOffset }
                    }
                }
        }
        return rows
    }

    /** The editions to ask between after an add that named none was refused for it. */
    var editions by mutableStateOf<List<Edition>>(emptyList())
        private set

    /**
     * Adds an item last; false when the server refused, as for a song in a movies playlist, or when
     * the title has several editions and [editionId] named none, which then fills [editions].
     */
    suspend fun add(itemId: String, playlistId: String, editionId: String? = null): Boolean {
        editions = emptyList()
        return addEntry(PlaylistEntryInput(itemId = itemId, editionId = editionId), playlistId) &&
            editions.isEmpty()
    }

    /**
     * Appends a selection in one request, to a playlist or a station's own; a title of several
     * editions takes its default one.
     */
    suspend fun add(itemIds: List<String>, playlistId: String): Boolean = write {
        api.call {
            playlists.addPlaylistEntries(
                profileId,
                playlistId,
                PlaylistEntriesInput(itemIds.map { PlaylistEntryInput(itemId = it) }),
            )
        }
        refresh(playlistId)
    }

    /** Adds a collection last, as one entry that the play order expands. */
    suspend fun addCollection(collectionId: String, playlistId: String): Boolean =
        addEntry(PlaylistEntryInput(collectionId = collectionId), playlistId)

    private suspend fun addEntry(input: PlaylistEntryInput, playlistId: String): Boolean = write {
        try {
            api.call { playlists.addPlaylistEntry(profileId, playlistId, input) }
        } catch (e: Exception) {
            val itemId = input.itemId
            if (itemId == null || input.editionId != null) throw e
            if (ApiError.from(e)?.fields?.contains("editionId") != true) throw e
            // The title has several editions and takes one by name; the adult picks from these.
            editions =
                api.call { items.getItem(itemId) }.editions?.takeIf { it.isNotEmpty() } ?: throw e
            return@write
        }
        refresh(playlistId)
    }

    suspend fun remove(entry: PlaylistEntry) = write {
        api.call { playlists.removePlaylistEntry(profileId, entry.playlistId, entry.id) }
        refresh(entry.playlistId)
    }

    /** Moves an entry to [position], counted from 0 at the top. */
    suspend fun move(entry: PlaylistEntry, position: Int) = write {
        api.call {
            playlists.replacePlaylistEntry(
                profileId,
                entry.playlistId,
                entry.id,
                PlaylistEntryInput(
                    itemId = entry.itemId,
                    collectionId = entry.collectionId,
                    position = maxOf(0, position),
                ),
            )
        }
        refresh(entry.playlistId)
    }

    /** Item counts, length and artwork change with every entry write. */
    private suspend fun refresh(id: String) {
        val fresh = api.call { playlists.getPlaylist(profileId, id) }
        playlists = playlists.map { if (it.id == id) fresh else it }
    }

    private suspend fun write(block: suspend () -> Unit): Boolean =
        try {
            block()
            error = null
            true
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
            false
        }

    companion object {
        const val PAGE = 200

        private fun name(name: String): String? = name.trim().take(100).ifEmpty { null }
    }
}
