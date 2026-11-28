package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.Collection
import enterprises.legitimate.rabbitears.api.models.CollectionInput
import enterprises.legitimate.rabbitears.api.models.CollectionMoviePositionInput
import enterprises.legitimate.rabbitears.api.models.CollectionSource
import enterprises.legitimate.rabbitears.api.models.Item

/** What the caller may see of it, which is all the server counts. */
val Collection.caption: String
    get() = Format.plural(movieCount, "movie")

/** Whether [adult] may rename, delete and edit the rules of it: a custom one, an adult's. */
fun Collection.manageable(adult: Boolean): Boolean = adult && source == CollectionSource.custom

/** Whether [adult] may add, remove and reorder its movies, which a smart one's rules choose. */
fun Collection.moviesEditable(adult: Boolean): Boolean = manageable(adult) && smart != true

/** The movies with [itemId] at [to], as a drag shows them before the server answers. */
fun List<Item>.moved(itemId: String, to: Int): List<Item> {
    val at = indexOfFirst { it.id == itemId }
    if (at < 0) return this
    return toMutableList().apply { add(to.coerceIn(0, lastIndex), removeAt(at)) }
}

/**
 * The movie collections the active profile may see. A TMDB collection is read-only and a custom one
 * belongs to the household; kids' writes are answered with 403, so the screens offer them none.
 */
class CollectionsModel(val api: Api, private val offline: OfflineLibrary? = null) {
    var collections by mutableStateOf<List<Collection>>(emptyList())
        private set

    var loaded by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** The ones a movie can be added to. */
    val custom: List<Collection>
        get() = collections.filter { it.source == CollectionSource.custom && it.smart != true }

    suspend fun load() {
        write {
            collections =
                offline.answer({ collections() }) {
                    pages { o ->
                        api.call { collections.listCollections(limit = PAGE, offset = o ?: 0) }
                            .let { it.items to it.nextOffset }
                    }
                }
        }
        loaded = true
    }

    /** One collection, fresh from the server, replacing its copy in the list; null when unseen. */
    suspend fun get(id: String): Collection? {
        var fresh: Collection? = null
        write {
            fresh =
                offline
                    .answer({ collection(id) }) { api.call { collections.getCollection(id) } }
                    ?.also(::replace)
        }
        return fresh
    }

    /** The movies in release order; null when they could not be read. */
    suspend fun movies(id: String): List<Item>? {
        var movies: List<Item>? = null
        write {
            movies =
                offline.answer({ collectionMovies(id) }) {
                    pages { o ->
                        api.call {
                                collections.listCollectionMovies(id, limit = PAGE, offset = o ?: 0)
                            }
                            .let { it.items to it.nextOffset }
                    }
                }
        }
        return movies
    }

    /** The collections a movie is in; only its details carry them. */
    suspend fun ofMovie(itemId: String): List<Collection> = runCatching {
        api.call { items.getItem(itemId) }.collections
    }
        .getOrNull()
        .orEmpty()

    suspend fun create(name: String): Collection? {
        val trimmed =
            name.trim().take(100).ifEmpty {
                return null
            }
        var created: Collection? = null
        write {
            created =
                api.call { collections.createCollection(CollectionInput(trimmed)) }
                    .also { collections = (collections + it).sortedBy { c -> c.name.lowercase() } }
        }
        return created
    }

    /**
     * Makes a smart collection: the collection, then its rules. A refusal of the rules deletes the
     * collection again, so none is left without them, and [draft] carries why.
     */
    suspend fun create(draft: SmartDraft): Collection? {
        if (draft.kind != null || !draft.validate()) return null
        return draft.submit {
            val made = api.call {
                collections.createCollection(CollectionInput(draft.name.trim().take(100)))
            }
            try {
                api.call { collections.replaceCollectionRules(made.id, draft.rules()) }
            } catch (e: Exception) {
                runCatching { api.call { collections.deleteCollection(made.id) } }
                throw e
            }
            api.call { collections.getCollection(made.id) }
                .also { collections = (collections + it).sortedBy { c -> c.name.lowercase() } }
        }
    }

    /** The smart collection's rules as a draft to edit; null when they could not be read. */
    suspend fun draft(collection: Collection): SmartDraft? {
        var draft: SmartDraft? = null
        write {
            val rules = api.call { collections.getCollectionRules(collection.id) }
            draft = SmartDraft(null, collection.name, rules)
        }
        return draft
    }

    /** Replaces a collection's rules, renaming it when its name changed. */
    suspend fun saveRules(id: String, draft: SmartDraft): Boolean {
        if (!draft.validate()) return false
        return draft.submit {
            api.call { collections.replaceCollectionRules(id, draft.rules()) }
            val trimmed = draft.name.trim().take(100)
            if (collections.firstOrNull { it.id == id }?.name?.let { it != trimmed } == true)
                api.call { collections.replaceCollection(id, CollectionInput(trimmed)) }
            replace(api.call { collections.getCollection(id) })
        } != null
    }

    suspend fun rename(collection: Collection, name: String): Boolean {
        val trimmed =
            name.trim().take(100).ifEmpty {
                return false
            }
        return write {
            replace(
                api.call { collections.replaceCollection(collection.id, CollectionInput(trimmed)) },
            )
        }
    }

    suspend fun delete(collection: Collection) = write {
        api.call { collections.deleteCollection(collection.id) }
        collections = collections.filter { it.id != collection.id }
    }

    suspend fun add(collectionId: String, itemId: String) = write {
        api.call { collections.addCollectionMovie(collectionId, itemId) }
        replace(api.call { collections.getCollection(collectionId) })
    }

    suspend fun remove(collectionId: String, itemId: String) = write {
        api.call { collections.removeCollectionMovie(collectionId, itemId) }
        replace(api.call { collections.getCollection(collectionId) })
    }

    /**
     * Puts a movie at [to], counted from 0 among the rows the caller shows; the server counts all
     * the movies, so with some hidden the index can differ. The caller reads the movies again.
     */
    suspend fun move(collectionId: String, itemId: String, to: Int) = write {
        replace(
            api.call {
                collections.replaceCollectionMoviePosition(
                    collectionId,
                    itemId,
                    CollectionMoviePositionInput(to),
                )
            },
        )
    }

    /** Back to release order. */
    suspend fun resetOrder(collectionId: String) = write {
        replace(api.call { collections.resetCollectionOrder(collectionId) })
    }

    /** A collection opened from a movie may not be in the list yet. */
    private fun replace(fresh: Collection) {
        collections =
            if (collections.any { it.id == fresh.id })
                collections.map { if (it.id == fresh.id) fresh else it }
            else collections + fresh
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
    }
}
