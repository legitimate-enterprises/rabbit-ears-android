package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Credit
import enterprises.legitimate.rabbitears.api.models.ItemCredits
import org.junit.Assert.assertEquals
import org.junit.Test

class CrewLinesTest {
    private val credits =
        ItemCredits(
            cast = emptyList(),
            crew =
                listOf(
                    Credit(1, "Denis Villeneuve", job = "Director"),
                    Credit(1, "Denis Villeneuve", job = "Screenplay"),
                    Credit(2, "Jon Spaihts", job = "Screenplay"),
                    Credit(3, "Hans Zimmer", job = "Original Music Composer"),
                ),
        )

    @Test
    fun namesDirectorsAndWritersAsTheBoardsDo() {
        assertEquals(
            listOf("Director" to "Denis Villeneuve", "Writers" to "Denis Villeneuve, Jon Spaihts"),
            crewLines(credits, episode = false),
        )
        assertEquals(
            listOf("Directed by", "Written by"),
            crewLines(credits, episode = true).map { it.first },
        )
        assertEquals(
            emptyList<Pair<String, String>>(),
            crewLines(ItemCredits(emptyList(), emptyList()), false),
        )
    }
}
