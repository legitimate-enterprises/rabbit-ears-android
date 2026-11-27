package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.CeilingSuggestion
import enterprises.legitimate.rabbitears.api.models.FilterMode
import enterprises.legitimate.rabbitears.api.models.MovieRating
import enterprises.legitimate.rabbitears.api.models.TvRating
import enterprises.legitimate.rabbitears.kit.AddKidForm.Field
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddKidFormTest {
    private val today = LocalDate.of(2026, 11, 14)

    @Test
    fun validatesNameBirthdayAndPin() {
        val form = AddKidForm(today)
        assertEquals("Enter a name.", form.errors[Field.Name])
        form.name = "x".repeat(41)
        assertEquals("Use 40 characters or fewer.", form.errors[Field.Name])
        form.name = " Joan "
        form.birthday = today.plusDays(1)
        assertEquals("Birthday can't be in the future.", form.errors[Field.Birthday])
        form.birthday = LocalDate.of(2017, 8, 19)
        form.requirePin = true
        form.pin = "12a4"
        assertEquals("Use four digits.", form.errors[Field.Pin])
        form.pin = "1234"
        form.pinConfirmation = "1235"
        assertEquals("PINs don't match.", form.errors[Field.Pin])
        form.pinConfirmation = "1234"
        assertTrue(form.errors.isEmpty())
    }

    @Test
    fun takesTheSuggestionUntilTheCeilingsArePickedByHand() {
        val form = AddKidForm(today)
        form.name = "Joan"
        form.apply(CeilingSuggestion(9, MovieRating.PG, TvRating.TVMinusPG))
        assertEquals(TvRating.TVMinusPG, form.tvCeiling)
        assertEquals("Joan is 9. Suggested: movies up to PG, TV up to TV-PG.", form.hint)
        form.pickMovieCeiling(MovieRating.G)
        form.apply(CeilingSuggestion(12, MovieRating.PGMinus13, TvRating.TVMinus14))
        assertEquals(MovieRating.G, form.movieCeiling)
        assertEquals(TvRating.TVMinusPG, form.tvCeiling)
    }

    @Test
    fun anAllowListSendsNoCeilings() {
        val form = AddKidForm(today)
        form.name = "Mary"
        form.filterMode = FilterMode.allow_list
        assertNull(form.input.movieCeiling)
        assertNull(form.input.tvCeiling)
        assertEquals("Mary", form.input.name)
    }

    @Test
    fun addingAKidSetsItsPinAndRereadsTheHousehold() = runBlocking {
        val server = FakeServer()
        val app = AppModel(MemoryCredentialStore(), "Pixel", server.http)
        server.on("POST /auth/sign-in", 201, """{"token":"t","me":${Json.me()}}""")
        app.signIn("192.168.1.20", "a@example.com", "pw")
        server.on("POST /profiles", 201, Json.account(5, "Joan", kind = "kid"))
        server.on("PUT /profiles/5/pin", 204)
        server.on(
            "GET /me",
            body = Json.me(1, Json.account(1, "Helga"), Json.account(5, "Joan", kind = "kid")),
        )
        val form =
            AddKidForm(today).apply {
                name = "Joan"
                birthday = LocalDate.of(2017, 8, 19)
                requirePin = true
                pin = "4321"
                pinConfirmation = "4321"
            }
        app.addKid(form)
        val create = server.called("POST /profiles").single().body
        assertTrue(create, create.contains(""""birthday":"2017-08-19""""))
        assertTrue(create, create.contains(""""movieCeiling":"PG""""))
        assertTrue(server.called("PUT /profiles/5/pin").single().body.contains("4321"))
        assertEquals(listOf("Helga", "Joan"), app.profiles.map { it.name })
    }
}
