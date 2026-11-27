package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.PlaylistShareAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSharesTest {
    private val server = FakeServer()
    private val model = PlaylistSharesModel(server.api(), 1, 1)

    private fun household(id: Long, name: String, profiles: Int = 2) =
        """{"id":$id,"name":"$name","profiles":$profiles}"""

    private fun householdShare(id: Long, name: String, access: String = "read") =
        """{"playlistId":1,"householdId":$id,"name":"$name","access":"$access",""" +
            """"createdAt":"${Json.T}"}"""

    /** The optional fields, left out when null as a server before them would. */
    private fun extra(householdId: Long?, color: String?) =
        listOfNotNull(
                householdId?.let { ""","householdId":$it""" },
                color?.let { ""","color":"$it"""" },
            )
            .joinToString("")

    private fun share(
        id: Long,
        name: String,
        kind: String = "adult",
        access: String = "read",
        householdId: Long? = null,
        color: String? = null,
    ) =
        """{"playlistId":1,"profileId":$id,"name":"$name","kind":"$kind","access":"$access",""" +
            """"createdAt":"${Json.T}"${extra(householdId, color)}}"""

    private fun candidate(
        id: Long,
        name: String,
        household: String,
        kind: String = "adult",
        householdId: Long? = 1,
        color: String? = null,
    ) =
        """{"profileId":$id,"name":"$name","kind":"$kind","householdName":"$household"""" +
            """${extra(householdId, color)}}"""

    private fun answer(
        households: List<String> = listOf(household(1, "Home")),
        householdShares: List<String> = emptyList(),
        candidates: List<String> = emptyList(),
        shares: List<String> = emptyList(),
    ) {
        server.on(
            "GET /profiles/1/share-households",
            body = """{"items":[${households.joinToString(",")}]}""",
        )
        server.on(
            "GET /profiles/1/playlists/1/household-shares",
            body = Json.page(*householdShares.toTypedArray()),
        )
        server.on("GET /profiles/1/share-candidates", body = Json.page(*candidates.toTypedArray()))
        server.on("GET /profiles/1/playlists/1/shares", body = Json.page(*shares.toTypedArray()))
    }

    private fun profile(id: Long) = model.profiles.single { it.profileId == id }

    @Test
    fun namesTheHouseholdByIdOnlyWhenTheOwnerIsInSeveral() = runBlocking {
        answer(
            households = listOf(household(1, "Home", 4), household(2, "Cabin", 1)),
            householdShares = listOf(householdShare(2, "Cabin", "write")),
            candidates =
                listOf(
                    candidate(4, "Ana", "Home", color = "#6B3A2E"),
                    candidate(5, "Max", "Cabin", "kid", householdId = 2),
                    candidate(6, "Zed", "Home", householdId = 7),
                ),
            shares =
                listOf(
                    share(4, "Ana", access = "write"),
                    share(9, "Ola", "kid", householdId = 2, color = "#2E6B5B"),
                ),
        )
        assertTrue(model.load())
        assertEquals(listOf("Home", "Cabin"), model.households.map { it.name })
        assertEquals(listOf(null, PlaylistShareAccess.write), model.households.map(model::access))
        assertEquals(listOf(4L, 5L, 6L, 9L), model.profiles.map { it.profileId })
        assertEquals(
            listOf("Home", "Cabin · Kid", "", "Cabin · Kid"),
            model.profiles.map { it.subtitle },
        )
        assertEquals(
            listOf(PlaylistShareAccess.write, null, null, PlaylistShareAccess.read),
            model.profiles.map { it.access },
        )
        assertEquals(listOf("#6B3A2E", null, null, "#2E6B5B"), model.profiles.map { it.color })
        assertEquals(listOf(null, PlaylistShareAccess.read), profile(5).choices)
        assertEquals(
            listOf(null, PlaylistShareAccess.read, PlaylistShareAccess.write),
            profile(4).choices,
        )
    }

    @Test
    fun oneHouseholdGoesUnnamed() = runBlocking {
        answer(candidates = listOf(candidate(4, "Ana", "Home"), candidate(5, "Max", "Home", "kid")))
        model.load()
        assertEquals(listOf("", "Kid"), model.profiles.map { it.subtitle })
    }

    @Test
    fun aHouseholdIsSharedChangedAndUnshared() = runBlocking {
        answer()
        model.load()
        val home = model.households.single()

        server.on("POST /profiles/1/playlists/1/household-shares", 201, householdShare(1, "Home"))
        answer(householdShares = listOf(householdShare(1, "Home")))
        assertTrue(model.set(home, PlaylistShareAccess.read))
        val body = server.called("POST /profiles/1/playlists/1/household-shares").single().body
        assertTrue(
            body,
            body.contains(""""householdId":1""") && body.contains(""""access":"read""""),
        )
        assertEquals(PlaylistShareAccess.read, model.access(home))

        server.on(
            "PUT /profiles/1/playlists/1/household-shares/1",
            body = householdShare(1, "Home", "write"),
        )
        answer(householdShares = listOf(householdShare(1, "Home", "write")))
        assertTrue(model.set(home, PlaylistShareAccess.write))
        assertTrue(
            server
                .called("PUT /profiles/1/playlists/1/household-shares/1")
                .single()
                .body
                .contains(""""access":"write""""),
        )
        assertEquals(PlaylistShareAccess.write, model.access(home))

        server.on("DELETE /profiles/1/playlists/1/household-shares/1", 204)
        answer()
        assertTrue(model.set(home, null))
        assertEquals(1, server.called("DELETE /profiles/1/playlists/1/household-shares/1").size)
        assertNull(model.access(home))
        assertNull(model.error)
    }

    @Test
    fun aProfileIsSharedChangedAndUnsharedAndAKidOnlyViews() = runBlocking {
        val people = listOf(candidate(4, "Ana", "Home"), candidate(5, "Max", "Home", "kid"))
        answer(candidates = people)
        model.load()

        server.on("POST /profiles/1/playlists/1/shares", 201, share(5, "Max", "kid"))
        answer(candidates = people, shares = listOf(share(5, "Max", "kid")))
        assertTrue(model.set(profile(5), PlaylistShareAccess.write))
        val body = server.called("POST /profiles/1/playlists/1/shares").single().body
        assertTrue(body, body.contains(""""profileId":5""") && body.contains(""""access":"read""""))
        assertEquals(PlaylistShareAccess.read, profile(5).access)

        server.on("POST /profiles/1/playlists/1/shares", 201, share(4, "Ana"))
        answer(candidates = people, shares = listOf(share(4, "Ana"), share(5, "Max", "kid")))
        model.set(profile(4), PlaylistShareAccess.read)

        server.on("PUT /profiles/1/playlists/1/shares/4", body = share(4, "Ana", access = "write"))
        answer(
            candidates = people,
            shares = listOf(share(4, "Ana", access = "write"), share(5, "Max", "kid")),
        )
        assertTrue(model.set(profile(4), PlaylistShareAccess.write))
        assertTrue(
            server
                .called("PUT /profiles/1/playlists/1/shares/4")
                .single()
                .body
                .contains(""""access":"write""""),
        )
        assertEquals(PlaylistShareAccess.write, profile(4).access)

        server.on("DELETE /profiles/1/playlists/1/shares/5", 204)
        answer(candidates = people, shares = listOf(share(4, "Ana", access = "write")))
        assertTrue(model.set(profile(5), null))
        assertEquals(1, server.called("DELETE /profiles/1/playlists/1/shares/5").size)
        assertNull(profile(5).access)
        assertNull(model.error)
    }

    @Test
    fun aRefusedChangeLeavesTheAccessAsItWasAndSaysWhy() = runBlocking {
        answer(candidates = listOf(candidate(4, "Ana", "Home")))
        model.load()
        server.error("POST /profiles/1/playlists/1/shares", 409, "conflict", "Already shared.")
        assertFalse(model.set(profile(4), PlaylistShareAccess.read))
        assertNull(profile(4).access)
        assertNotNull(model.error)
    }
}
