package enterprises.legitimate.rabbitears.kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KidAllowListTest {
    private val server = FakeServer()
    private val allow = KidAllowList(server.api(), "7")

    @Test
    fun readsWhichKidsAllowTheTitleAndTogglesEach() = runBlocking {
        server.on("GET /items/7/allowed-for", body = """{"profileIds":[3]}""")
        allow.load()
        assertEquals(setOf("3"), allow.allowed)

        server.on("PUT /profiles/4/allowed-items/7", 204)
        allow.toggle("4")
        assertEquals(setOf("3", "4"), allow.allowed)

        server.on("DELETE /profiles/3/allowed-items/7", 204)
        allow.toggle("3")
        assertEquals(setOf("4"), allow.allowed)
        assertNull(allow.error)
    }

    @Test
    fun aRefusedToggleLeavesTheListAsItWas() = runBlocking {
        server.on("GET /items/7/allowed-for", body = """{"profileIds":[]}""")
        allow.load()
        server.error("PUT /profiles/4/allowed-items/7", 403, "forbidden")
        allow.toggle("4")
        assertTrue(allow.allowed.isEmpty())
        assertEquals("forbidden", allow.error)
    }
}

class ProfileColorsTest {
    @Test
    fun theServersEightColorsParseAndAnythingElseFallsBackToTheFirst() {
        assertEquals(8, ProfileColors.PALETTE.size)
        assertEquals(0xFF2E6B5BL, ProfileColors.argb("#2E6B5B"))
        assertEquals(0xFF3C4170L, ProfileColors.argb(null))
        assertEquals(0xFF3C4170L, ProfileColors.argb("teal"))
    }
}
