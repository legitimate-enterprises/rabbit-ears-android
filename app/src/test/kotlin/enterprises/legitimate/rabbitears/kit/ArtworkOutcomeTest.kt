package enterprises.legitimate.rabbitears.kit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkOutcomeTest {
    @Test
    fun aMissingImageIsRememberedAndATransientFailureIsNot() {
        assertTrue(remembersArtwork(status = 200, decoded = true))
        assertTrue(remembersArtwork(status = 404, decoded = false))
        assertFalse(remembersArtwork(status = null, decoded = false))
        assertFalse(remembersArtwork(status = 503, decoded = false))
        assertFalse(remembersArtwork(status = 200, decoded = false))
    }
}
