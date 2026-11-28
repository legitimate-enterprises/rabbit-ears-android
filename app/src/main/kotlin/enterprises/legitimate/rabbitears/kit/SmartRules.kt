package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.MovieRating
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.SmartCondition
import enterprises.legitimate.rabbitears.api.models.SmartField
import enterprises.legitimate.rabbitears.api.models.SmartField.album
import enterprises.legitimate.rabbitears.api.models.SmartField.artist
import enterprises.legitimate.rabbitears.api.models.SmartField.cast
import enterprises.legitimate.rabbitears.api.models.SmartField.contentRating
import enterprises.legitimate.rabbitears.api.models.SmartField.director
import enterprises.legitimate.rabbitears.api.models.SmartField.duration
import enterprises.legitimate.rabbitears.api.models.SmartField.episode
import enterprises.legitimate.rabbitears.api.models.SmartField.explicit
import enterprises.legitimate.rabbitears.api.models.SmartField.favorite
import enterprises.legitimate.rabbitears.api.models.SmartField.genre
import enterprises.legitimate.rabbitears.api.models.SmartField.hdr
import enterprises.legitimate.rabbitears.api.models.SmartField.lossless
import enterprises.legitimate.rabbitears.api.models.SmartField.playCount
import enterprises.legitimate.rabbitears.api.models.SmartField.runtime
import enterprises.legitimate.rabbitears.api.models.SmartField.season
import enterprises.legitimate.rabbitears.api.models.SmartField.show
import enterprises.legitimate.rabbitears.api.models.SmartField.title
import enterprises.legitimate.rabbitears.api.models.SmartField.uhd
import enterprises.legitimate.rabbitears.api.models.SmartField.watched
import enterprises.legitimate.rabbitears.api.models.SmartField.year
import enterprises.legitimate.rabbitears.api.models.SmartGroup
import enterprises.legitimate.rabbitears.api.models.SmartMatch
import enterprises.legitimate.rabbitears.api.models.SmartOperator
import enterprises.legitimate.rabbitears.api.models.SmartRules
import enterprises.legitimate.rabbitears.api.models.TvRating

/** A field's value type, which decides its operators and its value input. */
enum class SmartType {
    Text,
    Number,
    Boolean,
}

/** The fields each kind's rules may test, as the server's catalog has them. */
object SmartCatalog {
    /** Read from the list owner's own data; a collection takes none of them. */
    val PERSONAL = setOf(watched, playCount, favorite)

    private val MOVIES =
        listOf(
            title,
            year,
            runtime,
            contentRating,
            genre,
            cast,
            director,
            hdr,
            uhd,
            watched,
            playCount,
            favorite,
        )
    private val TV =
        listOf(
            title,
            show,
            season,
            episode,
            year,
            runtime,
            contentRating,
            genre,
            cast,
            hdr,
            uhd,
            watched,
            playCount,
            favorite,
        )
    private val MUSIC =
        listOf(title, artist, album, year, genre, duration, explicit, lossless, playCount, favorite)

    /** The fields of a playlist of [kind]; null is a collection's. */
    fun fields(kind: PlaylistKind?): List<SmartField> =
        when (kind) {
            PlaylistKind.movies -> MOVIES
            PlaylistKind.tv -> TV
            PlaylistKind.music -> MUSIC
            null -> MOVIES - PERSONAL
        }

    fun type(field: SmartField): SmartType =
        when (field) {
            year,
            runtime,
            playCount,
            season,
            episode,
            duration -> SmartType.Number
            hdr,
            uhd,
            watched,
            favorite,
            explicit,
            lossless -> SmartType.Boolean
            else -> SmartType.Text
        }

    /** The operators [field] takes, its type's first being the one a new field starts with. */
    fun operators(field: SmartField): List<SmartOperator> =
        when (type(field)) {
            SmartType.Text ->
                listOf(
                    SmartOperator.`is`,
                    SmartOperator.isNot,
                    SmartOperator.contains,
                    SmartOperator.notContains,
                    SmartOperator.startsWith,
                    SmartOperator.endsWith,
                )
            SmartType.Number ->
                listOf(
                    SmartOperator.`is`,
                    SmartOperator.isNot,
                    SmartOperator.lt,
                    SmartOperator.le,
                    SmartOperator.gt,
                    SmartOperator.ge,
                )
            SmartType.Boolean -> listOf(SmartOperator.isTrue, SmartOperator.isFalse)
        }

    fun label(field: SmartField): String =
        when (field) {
            title -> "Title"
            year -> "Year"
            runtime -> "Runtime"
            contentRating -> "Content rating"
            genre -> "Genre"
            cast -> "Cast"
            director -> "Director"
            hdr -> "HDR"
            uhd -> "4K"
            watched -> "Watched"
            playCount -> "Plays"
            favorite -> "Favorite"
            show -> "Show"
            season -> "Season"
            episode -> "Episode"
            artist -> "Artist"
            album -> "Album"
            duration -> "Duration"
            explicit -> "Explicit"
            lossless -> "Lossless"
        }

    /** What [operator] reads as on [field]: is and is not are = and ≠ between numbers. */
    fun label(operator: SmartOperator, field: SmartField): String {
        val number = type(field) == SmartType.Number
        return when (operator) {
            SmartOperator.`is` -> if (number) "=" else "is"
            SmartOperator.isNot -> if (number) "≠" else "is not"
            SmartOperator.contains -> "contains"
            SmartOperator.notContains -> "does not contain"
            SmartOperator.startsWith -> "starts with"
            SmartOperator.endsWith -> "ends with"
            SmartOperator.lt -> "less than"
            SmartOperator.le -> "at most"
            SmartOperator.gt -> "more than"
            SmartOperator.ge -> "at least"
            SmartOperator.isTrue -> "Yes"
            SmartOperator.isFalse -> "No"
        }
    }

    fun unit(field: SmartField): String? =
        when (field) {
            runtime -> "min"
            duration -> "s"
            season -> "season"
            episode -> "episode"
            else -> null
        }

    /** Values to offer for [field] on a list of [kind]: the rating scale of its titles. */
    fun suggestions(field: SmartField, kind: PlaylistKind?): List<String> =
        when {
            field != contentRating -> emptyList()
            kind == PlaylistKind.tv -> TvRating.entries.map { it.value }
            else -> MovieRating.entries.map { it.value }
        }
}

/** One condition row as typed: [value] is the text field's content for any type but boolean. */
data class ConditionDraft(
    val field: SmartField = title,
    val operator: SmartOperator = SmartCatalog.operators(field).first(),
    val value: String = "",
)

data class GroupDraft(
    val match: SmartMatch = SmartMatch.all,
    val conditions: List<ConditionDraft> = listOf(ConditionDraft()),
)

/**
 * A smart list being written: a playlist of [kind], or a collection when [kind] is null. Rows are
 * addressed by group and condition index, which is also how the server's 400 names them.
 */
class SmartDraft(kind: PlaylistKind?, name: String = "", rules: SmartRules? = null) {
    var kind by mutableStateOf(kind)
        private set

    var name by mutableStateOf(name)

    var match by mutableStateOf(rules?.match ?: SmartMatch.all)

    var groups by
        mutableStateOf(
            rules?.groups?.map { g ->
                GroupDraft(
                    g.match,
                    g.conditions.map {
                        ConditionDraft(
                            it.field,
                            it.operator,
                            it.text ?: it.number?.toString().orEmpty(),
                        )
                    },
                )
            } ?: listOf(GroupDraft()),
        )
        private set

    /** Each row's refusal, by (group, condition). */
    var errors by mutableStateOf<Map<Pair<Int, Int>, String>>(emptyMap())
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    /** A refusal that names no row. */
    var error by mutableStateOf<String?>(null)
        private set

    val fields: List<SmartField>
        get() = SmartCatalog.fields(kind)

    /** Whether a row reads the owner's own data, which the editor's footnote then says. */
    val personal: Boolean
        get() = groups.any { g -> g.conditions.any { it.field in SmartCatalog.PERSONAL } }

    /** Whether a row may go; the rules keep at least one condition. */
    val removable: Boolean
        get() = groups.size > 1 || groups.single().conditions.size > 1

    /** A new playlist's kind; a row whose field the kind lacks starts over at Title. */
    fun choose(kind: PlaylistKind) {
        this.kind = kind
        groups.forEachIndexed { g, group ->
            group.conditions.forEachIndexed { c, row ->
                if (row.field !in fields) setField(g, c, fields.first())
            }
        }
    }

    /** Resets the operator to the field's first, keeping the value only for a field of its type. */
    fun setField(g: Int, c: Int, field: SmartField) =
        update(g, c) {
            val same = SmartCatalog.type(field) == SmartCatalog.type(it.field)
            ConditionDraft(field, value = if (same) it.value else "")
        }

    fun setOperator(g: Int, c: Int, operator: SmartOperator) =
        update(g, c) { it.copy(operator = operator) }

    fun setValue(g: Int, c: Int, value: String) = update(g, c) { it.copy(value = value) }

    fun setMatch(g: Int, match: SmartMatch) {
        groups = groups.mapIndexed { i, group -> if (i == g) group.copy(match = match) else group }
    }

    fun addCondition(g: Int) {
        groups = groups.mapIndexed { i, group ->
            if (i == g) group.copy(conditions = group.conditions + ConditionDraft(fields.first()))
            else group
        }
        errors = emptyMap()
    }

    fun addGroup() {
        groups = groups + GroupDraft(conditions = listOf(ConditionDraft(fields.first())))
    }

    /** Removes a row, and its group with its last row. */
    fun remove(g: Int, c: Int) {
        if (!removable) return
        groups =
            groups
                .mapIndexed { i, group ->
                    if (i == g)
                        group.copy(conditions = group.conditions.filterIndexed { j, _ -> j != c })
                    else group
                }
                .filter { it.conditions.isNotEmpty() }
        errors = emptyMap()
    }

    /** The client's checks: a name, text not blank, a number an integer. */
    fun validate(): Boolean {
        nameError = if (name.isBlank()) "Name it." else null
        errors = buildMap {
            groups.forEachIndexed { g, group ->
                group.conditions.forEachIndexed { c, row ->
                    when (SmartCatalog.type(row.field)) {
                        SmartType.Text -> if (row.value.isBlank()) put(g to c, "Enter some text.")
                        SmartType.Number ->
                            if (row.value.trim().toLongOrNull() == null)
                                put(g to c, "Enter a whole number.")
                        SmartType.Boolean -> Unit
                    }
                }
            }
        }
        error = null
        return nameError == null && errors.isEmpty()
    }

    /** The request body; call after [validate] passes. */
    fun rules(): SmartRules =
        SmartRules(
            match,
            groups.map { g ->
                SmartGroup(
                    g.match,
                    g.conditions.map {
                        val type = SmartCatalog.type(it.field)
                        SmartCondition(
                            it.field,
                            it.operator,
                            text = it.value.trim().takeIf { type == SmartType.Text },
                            number =
                                it.value.trim().toLongOrNull()?.takeIf { type == SmartType.Number },
                        )
                    },
                )
            },
        )

    /**
     * Runs a save, which throws on refusal; a 400's `groups.i.conditions.j.*` fields land on their
     * rows and anything else in [error].
     */
    suspend fun <T> submit(block: suspend () -> T): T? =
        try {
            block().also { error = null }
        } catch (e: Exception) {
            val fields = ApiError.from(e)?.fields.orEmpty()
            val rows = mutableMapOf<Pair<Int, Int>, String>()
            val rest = mutableListOf<String>()
            fields.forEach { (path, message) ->
                val at = ROW.matchEntire(path)?.groupValues
                if (at != null) rows[at[1].toInt() to at[2].toInt()] = message
                else if (path == "name") nameError = message else rest += message
            }
            errors = rows
            error =
                rest.firstOrNull()
                    ?: AppError.from(e, AppError.SignedOut).message.takeIf { fields.isEmpty() }
            null
        }

    private fun update(g: Int, c: Int, change: (ConditionDraft) -> ConditionDraft) {
        groups = groups.mapIndexed { i, group ->
            if (i != g) group
            else
                group.copy(
                    conditions =
                        group.conditions.mapIndexed { j, row -> if (j == c) change(row) else row },
                )
        }
        errors = errors - (g to c)
    }

    companion object {
        private val ROW = Regex("""groups\.(\d+)\.conditions\.(\d+)\.\w+""")
    }
}
