package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.CeilingSuggestion
import enterprises.legitimate.rabbitears.api.models.FilterMode
import enterprises.legitimate.rabbitears.api.models.MovieRating
import enterprises.legitimate.rabbitears.api.models.ProfileInput
import enterprises.legitimate.rabbitears.api.models.TvRating
import java.time.LocalDate

/** The Add kid form, also used to edit [editing], whose PIN stays when the PIN fields are blank. */
class AddKidForm(private val today: LocalDate = LocalDate.now(), val editing: Account? = null) {
    enum class Field {
        Name,
        Birthday,
        Pin,
    }

    /** Whether saving sets a new PIN, clears the old one, or leaves it. */
    sealed interface PinChange {
        data object Keep : PinChange

        data object Clear : PinChange

        data class Set(val pin: String) : PinChange
    }

    var name by mutableStateOf("")
    var birthday: LocalDate by mutableStateOf(today.minusYears(8))
    var filterMode by mutableStateOf(FilterMode.ratings)
    var movieCeiling by mutableStateOf(MovieRating.PG)
        private set

    var tvCeiling by mutableStateOf(TvRating.TVMinusY7)
        private set

    var requirePin by mutableStateOf(false)
    var pin by mutableStateOf("")
    var pinConfirmation by mutableStateOf("")
    var suggestion by mutableStateOf<CeilingSuggestion?>(null)
        private set

    private var ceilingsEdited = false

    init {
        editing?.let { kid ->
            name = kid.name
            kid.birthday?.let { birthday = it }
            filterMode = kid.filterMode
            kid.movieCeiling?.let { movieCeiling = it }
            kid.tvCeiling?.let { tvCeiling = it }
            ceilingsEdited = true
            requirePin = kid.hasPin
        }
    }

    val pinChange: PinChange
        get() {
            val had = editing?.hasPin == true
            if (requirePin) return if (pin.isEmpty() && had) PinChange.Keep else PinChange.Set(pin)
            return if (had) PinChange.Clear else PinChange.Keep
        }

    val trimmedName: String
        get() = name.trim()

    val errors: Map<Field, String>
        get() = buildMap {
            if (trimmedName.isEmpty()) put(Field.Name, "Enter a name.")
            else if (trimmedName.length > 40) put(Field.Name, "Use 40 characters or fewer.")
            if (birthday.isAfter(today)) put(Field.Birthday, "Birthday can't be in the future.")
            if (requirePin && pinChange != PinChange.Keep) {
                if (pin.length != 4 || !pin.all { it in '0'..'9' })
                    put(Field.Pin, "Use four digits.")
                else if (pin != pinConfirmation) put(Field.Pin, "PINs don't match.")
            }
        }

    val input: ProfileInput
        get() {
            val ratings = filterMode == FilterMode.ratings
            return ProfileInput(
                trimmedName,
                birthday,
                filterMode,
                if (ratings) movieCeiling else null,
                if (ratings) tvCeiling else null,
            )
        }

    fun pickMovieCeiling(rating: MovieRating) {
        movieCeiling = rating
        ceilingsEdited = true
    }

    fun pickTvCeiling(rating: TvRating) {
        tvCeiling = rating
        ceilingsEdited = true
    }

    /** Takes the server's suggestion for the birthday unless the ceilings were picked by hand. */
    fun apply(suggestion: CeilingSuggestion) {
        this.suggestion = suggestion
        if (ceilingsEdited) return
        movieCeiling = suggestion.movieCeiling
        tvCeiling = suggestion.tvCeiling
    }

    val hint: String?
        get() = suggestion?.let {
            val who = if (trimmedName.isEmpty()) "They are" else "$trimmedName is"
            "$who ${it.age}. Suggested: movies up to ${it.movieCeiling.value}, TV up to ${it.tvCeiling.value}."
        }

    companion object {
        /** NC-17 is never a kid's ceiling. */
        val movieRatings =
            listOf(MovieRating.G, MovieRating.PG, MovieRating.PGMinus13, MovieRating.R)
        val tvRatings = TvRating.entries.toList()
    }
}
