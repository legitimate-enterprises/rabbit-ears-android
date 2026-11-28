package enterprises.legitimate.rabbitears.ui

import enterprises.legitimate.rabbitears.kit.AppModel
import enterprises.legitimate.rabbitears.kit.CatalogModel
import enterprises.legitimate.rabbitears.kit.FakeServer
import enterprises.legitimate.rabbitears.kit.Json
import enterprises.legitimate.rabbitears.kit.MemoryCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private val server = FakeServer()
    private val app = AppModel(MemoryCredentialStore(), "Pixel", server.http)

    @Test
    fun replacingTakesThePlaceOfTheTopScreenSoBackSkipsIt() {
        val nav = Navigator(app, CatalogModel(server.api(), "1"))
        nav.open(Route.Search)
        nav.open(Route.Details("2"))
        nav.replace(Route.Details("3"))
        assertEquals(listOf(Route.Search, Route.Details("3")), nav.stack.toList())
        nav.back()
        assertEquals(listOf<Route>(Route.Search), nav.stack.toList())
    }

    /** The server offers a kid Watch together only on titles the kid may see. */
    @Test
    fun aKidWatchesTogetherAsAnAdultDoes() = runBlocking {
        val kid = Json.account("3", "Joan", kind = "kid")
        server.on(
            "POST /auth/sign-in",
            201,
            """{"token":"t1","me":${Json.me(1, Json.account("1", "Helga"), kid)}}""",
        )
        server.on("POST /profiles/3/unlock", 204)
        app.signIn("192.168.1.20", "a@example.com", "pw")
        app.choose(app.profiles.last())
        val nav = Navigator(app, CatalogModel(server.api(), "3"))

        val together = nav.together("101")
        assertNotNull(together)
        assertSame(together, nav.together("101"))
        assertEquals("101", together?.itemId)
    }

    /** A kid's Preferences has no Friends, and nothing about friends is asked for. */
    @Test
    fun onlyAnAdultHasFriends() = runBlocking {
        val kid = Json.account("3", "Joan", kind = "kid")
        server.on(
            "POST /auth/sign-in",
            201,
            """{"token":"t1","me":${Json.me(1, Json.account("1", "Helga"), kid)}}""",
        )
        server.on("POST /profiles/3/unlock", 204)
        app.signIn("192.168.1.20", "a@example.com", "pw")
        assertTrue(Navigator(app, CatalogModel(server.api(), "1")).friends.adult)
        app.choose(app.profiles.last())
        val friends = Navigator(app, CatalogModel(server.api(), "3")).friends
        assertFalse(friends.adult)
        val asked = server.calls.size
        friends.load()
        assertEquals(asked, server.calls.size)
        assertEquals(0, friends.badge)
    }
}
