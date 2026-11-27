package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Edition
import enterprises.legitimate.rabbitears.api.models.EditionCopy
import enterprises.legitimate.rabbitears.api.models.WatchState
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditionsTest {
    private val standard =
        Edition("31", "Standard", isDefault = true, listOf(EditionCopy("1", "1080p")), 8_160_000)
    private val extended =
        Edition(
            "32",
            "Extended",
            isDefault = false,
            listOf(EditionCopy("2", "4K"), EditionCopy("3", "1080p"), EditionCopy("4", "1080p")),
            13_680_000,
        )
    private val both = listOf(standard, extended)

    private fun watch(position: Long, edition: String? = null, watched: Boolean = false) =
        WatchState("7", position, watched, 1, OffsetDateTime.parse(Json.T), edition)

    @Test
    fun theChoiceStartsOnTheEditionHoldingThePositionElseTheDefault() {
        assertEquals(extended, Editions.preselected(both, watch(60_000, "32")))
        assertEquals(standard, Editions.preselected(both, watch(60_000, "31")))
        assertEquals(standard, Editions.preselected(both, null))
        assertEquals(standard, Editions.preselected(listOf(extended, standard), watch(0)))
    }

    @Test
    fun resumeIsOfferedOnlyForTheEditionHoldingThePosition() {
        assertTrue(Editions.resumes(both, extended, watch(60_000, "32")))
        assertFalse(Editions.resumes(both, standard, watch(60_000, "32")))
        // A position that names no edition belongs to the default.
        assertTrue(Editions.resumes(both, standard, watch(60_000)))
        assertFalse(Editions.resumes(both, extended, watch(60_000)))
        assertFalse(Editions.resumes(both, extended, watch(0, "32")))
        assertTrue(Editions.resumes(both, extended, watch(60_000, "32", watched = true)))
    }

    @Test
    fun oneEditionOrNoneResumesAsBefore() {
        assertTrue(Editions.resumes(listOf(standard), standard, watch(60_000, "99")))
        assertTrue(Editions.resumes(emptyList(), null, watch(60_000)))
        assertFalse(Editions.resumes(emptyList(), null, null))
    }

    @Test
    fun aChoiceReadsItsNameRuntimeAndQualities() {
        assertEquals("Extended · 3h 48m · 4K, 1080p", Editions.label(extended))
        assertEquals("Bare", Editions.label(Edition("33", "Bare", false, listOf(EditionCopy("5")))))
    }

    @Test
    fun onlyATitleWithSeveralEditionsNamesOne() {
        assertEquals("32", Editions.requested(both, extended))
        assertNull(Editions.requested(listOf(standard), standard))
        assertNull(Editions.requested(emptyList(), null))
    }

    @Test
    fun detailsDescribeTheChosenEditionsFirstCopy() {
        assertEquals("2", Editions.described(both, extended))
        assertEquals("1", Editions.described(both, standard))
        // One edition, or none known: the item's first file.
        assertNull(Editions.described(listOf(extended), extended))
        assertNull(Editions.described(emptyList(), null))
        assertNull(
            Editions.described(
                both + Edition("33", "Empty", false, emptyList()),
                Edition("33", "Empty", false, emptyList()),
            ),
        )
    }

    @Test
    fun aRowNamesItsEditionAfterTheTitle() {
        assertEquals("Night Ferry · Extended", Editions.titled("Night Ferry", "Extended"))
        assertEquals("Night Ferry", Editions.titled("Night Ferry", null))
    }
}
