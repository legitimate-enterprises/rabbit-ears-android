package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTest {
    @Test
    fun parsesWhatPeopleTypeInTheServerField() {
        assertEquals(
            "http://media.example.com:8477",
            ServerEndpoint.parse("media.example.com")?.base,
        )
        assertEquals(
            "http://media.example.com:9000",
            ServerEndpoint.parse(" Media.Example.com:9000 ")?.base,
        )
        assertEquals(
            "https://media.example.com",
            ServerEndpoint.parse("https://media.example.com/join/AB")?.base,
        )
        assertEquals("http://192.168.1.4:8477", ServerEndpoint.parse("192.168.1.4")?.base)
        assertNull(ServerEndpoint.parse(""))
        assertNull(ServerEndpoint.parse("ftp://media.example.com"))
        assertNull(ServerEndpoint.parse("media example.com"))
    }

    @Test
    fun theServerFieldShowsAnAddressThatReadsBackTheSame() {
        for (base in
            listOf(
                "http://media.example.com:8477",
                "https://media.example.com:8477",
                "http://media.example.com",
                "https://media.example.com",
                "http://[fd00::1]:8477",
            )) assertEquals(base, ServerEndpoint.parse(ServerEndpoint(base).typed)?.base)
        assertEquals("media.example.com", ServerEndpoint("http://media.example.com:8477").typed)
    }

    @Test
    fun knowsAddressesOnlyALocalNetworkReaches() {
        for (local in
            listOf(
                "192.168.1.4",
                "10.0.0.2",
                "172.20.1.1",
                "nas.local",
                "localhost",
                "[fd00::1]",
            )) assertTrue(local, ServerEndpoint.parse(local)!!.isLocalNetwork)
        for (away in
            listOf(
                "media.example.com",
                "172.40.1.1",
                "8.8.8.8",
                "10.0.0.1.example.com",
            )) assertFalse(
            away,
            ServerEndpoint.parse(away)!!.isLocalNetwork,
        )
    }

    @Test
    fun sendsTheDeviceTokenAndDecodesTheAnswer() {
        val server = FakeServer()
        server.on("GET /items", body = Json.page(Json.item(7, "movie", "Flow", runtime = 85)))
        val page = server.api("secret").items.listItems(kind = ItemKind.movie)
        assertEquals("Flow", page.items.single().metadata?.title)
        assertEquals(ItemKind.movie, page.items.single().kind)
        assertEquals("Bearer secret", server.calls.single().auth)
        assertTrue(server.calls.single().query!!.contains("kind=movie"))
    }

    @Test
    fun turnsErrorAnswersIntoApiErrors() {
        val server = FakeServer()
        server.error("GET /me", 401, "unauthorized", "Sign in again.")
        val error = ApiError.from(runCatching { server.api().auth.getMe() }.exceptionOrNull()!!)
        assertEquals(401, error?.status)
        assertEquals("unauthorized", error?.code)
        assertEquals("Sign in again.", error?.message)

        server.on("GET /items", 500, "oops")
        val broken =
            ApiError.from(runCatching { server.api().items.listItems() }.exceptionOrNull()!!)
        assertEquals(500, broken?.status)
        assertEquals("internal", broken?.code)
    }
}
