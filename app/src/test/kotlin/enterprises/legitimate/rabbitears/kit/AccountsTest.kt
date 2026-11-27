package enterprises.legitimate.rabbitears.kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {
    private val server = FakeServer()
    private val store = MemoryCredentialStore()
    private val app = AppModel(store, "Pixel 9", server.http)

    private suspend fun signedIn() {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        app.signIn("192.168.1.20", "helga@example.com", "pw")
    }

    @Test
    fun theOwnerAddsAnAdultAndGetsTheCodeToHandOver() = runBlocking {
        signedIn()
        assertTrue(app.isOwner)
        val bea = Json.account(3, "Bea", pending = true)
        val code =
            """{"id":1,"code":"7KQ4-MNP2","link":"/join/7KQ4-MNP2","accountId":3,""" +
                """"expiresAt":"${Json.T}","createdAt":"${Json.T}"}"""
        server.on("POST /household/adults", 201, """{"account":$bea,"joinCode":$code}""")
        server.on("GET /me", body = Json.me(1, Json.account(1, "Helga"), bea))
        val invite = app.addAdult("Bea", "bea@example.com")
        assertEquals(Invite("7KQ4-MNP2", "http://192.168.1.20:8477/join/7KQ4-MNP2"), invite)
        val sent = server.called("POST /household/adults").single().body
        assertTrue(sent, sent.contains(""""name":"Bea"""") && sent.contains("bea@example.com"))
        assertEquals("Bea", app.me?.profiles?.last()?.name)

        server.error("POST /household/adults", 409, "conflict")
        val error = runCatching { app.addAdult("Bea", "bea@example.com") }.exceptionOrNull()
        assertEquals(AppError.EmailTaken, error)
    }

    @Test
    fun aJoinCodeIsReadWhateverItsCaseAndDashes() {
        assertEquals("7KQ4-MNP2", JoinCode.normalize("7kq4mnp2"))
        assertEquals("7KQ4-MNP2", JoinCode.normalize(" 7KQ4-mnp2 "))
        assertNull(JoinCode.normalize("7KQ4-MNP0"))
        assertNull(JoinCode.normalize("OKQ4-MNP2"))
        assertNull(JoinCode.normalize("7KQ4-MNP"))
        val (server, code) = JoinCode.parseLink("https://media.example.com/join/7kq4-mnp2")!!
        assertEquals("https://media.example.com", server.base)
        assertEquals("7KQ4-MNP2", code)
        assertNull(JoinCode.parseLink("7KQ4-MNP2"))
    }

    @Test
    fun aJoinCodeNamesWhoIsInvitedAndByWhom() = runBlocking {
        server.on(
            "GET /join/7KQ4-MNP2",
            body = """{"name":"Bea","expiresAt":"${Json.T}","invitedBy":"Helga"}""",
        )
        val preview = app.joinPreview("192.168.1.20", "7kq4mnp2")
        assertEquals("Bea", preview.name)
        assertEquals("Helga", preview.invitedBy)
        assertNull(server.calls.single().auth)
    }

    @Test
    fun anUnknownOrMalformedJoinCodeIsNoLongerValid() = runBlocking {
        server.error("GET /join/7KQ4-MNP2", 404, "not_found")
        for (code in listOf("7KQ4-MNP2", "nope")) assertEquals(
            AppError.InviteInvalid,
            runCatching { app.joinPreview("192.168.1.20", code) }.exceptionOrNull(),
        )
        assertEquals(1, server.calls.size)
        val away = runCatching { app.joinPreview("media.example.com", "7KQ4-MNP2") }
        assertEquals(AppError.InsecureServer, away.exceptionOrNull())
    }

    @Test
    fun acceptingAJoinCodeCreatesTheAccountAndSignsIn() = runBlocking {
        server.on("POST /join/7KQ4-MNP2", 201, """{"token":"t2","me":${Json.me()}}""")
        server.on("POST /me/lock", 204)
        app.acceptJoin("192.168.1.20", "7kq4mnp2", "Bea", "a long password")
        assertEquals(AppModel.Phase.ChoosingProfile, app.phase)
        assertEquals("t2", store.token)
        assertEquals("http://192.168.1.20:8477", store.server)
        val body = server.called("POST /join/7KQ4-MNP2").single().body
        assertTrue(
            body,
            body.contains(""""name":"Bea"""") && body.contains(""""platform":"android""""),
        )

        server.error("POST /join/7KQ4-MNP2", 404, "not_found", "join code")
        val used = runCatching { app.acceptJoin("192.168.1.20", "7KQ4-MNP2", "Bea", "pw") }
        assertEquals(AppError.InviteInvalid, used.exceptionOrNull())
    }

    private val twoHouseholds = """"households":[{"id":1,"name":"Jo"},{"id":7,"name":"Mark"}]"""

    @Test
    fun anAdultInSeveralHouseholdsPicksThisDevicesBeforeWhoIsWatching() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()},$twoHouseholds}""")
        server.on("POST /me/lock", 204)
        app.signIn("192.168.1.20", "jo@example.com", "pw")
        assertEquals(AppModel.Phase.ChoosingHousehold, app.phase)
        assertEquals(listOf("Jo", "Mark"), app.households.map { it.name })
        assertTrue(server.called("POST /me/lock").isEmpty())

        app.chooseHousehold(app.households.first())
        assertEquals(AppModel.Phase.ChoosingProfile, app.phase)
        assertTrue(server.called("PUT /me/household").isEmpty())
        assertEquals(1, server.called("POST /me/lock").size)
        assertTrue(app.households.isEmpty())
    }

    @Test
    fun anotherHouseholdMovesTheDeviceThereFirst() = runBlocking {
        server.on(
            "POST /join/7KQ4-MNP2",
            201,
            """{"token":"t2","me":${Json.me()},$twoHouseholds}""",
        )
        app.acceptJoin("192.168.1.20", "7KQ4-MNP2", "Jo", "a long password")
        assertEquals(AppModel.Phase.ChoosingHousehold, app.phase)
        server.on("PUT /me/household", 204)
        val there = Json.me(1, Json.account(1, "Helga"), Json.account(8, "Jason", kind = "kid"))
        server.on(
            "GET /me",
            body = there.replace(""""name":"Helga","adultId"""", """"name":"Mark","adultId""""),
        )
        server.on("POST /me/lock", 204)
        app.chooseHousehold(app.households.last())
        assertEquals("""{"householdId":7}""", server.called("PUT /me/household").single().body)
        assertEquals(AppModel.Phase.ChoosingProfile, app.phase)
        assertEquals("Mark", app.householdName)
        assertEquals(listOf("Helga", "Jason"), app.profiles.map { it.name })
    }

    @Test
    fun oneHouseholdGoesStraightToWhoIsWatching() = runBlocking {
        val one = """"households":[{"id":1,"name":"Jo"}]"""
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()},$one}""")
        server.on("POST /me/lock", 204)
        app.signIn("192.168.1.20", "jo@example.com", "pw")
        assertEquals(AppModel.Phase.ChoosingProfile, app.phase)
    }

    @Test
    fun changingThePasswordSendsBothAndSaysWhenTheCurrentIsWrong() = runBlocking {
        signedIn()
        server.error("PUT /me/password", 403, "forbidden")
        val wrong = runCatching { app.changePassword("nope", "a much better one") }
        assertEquals(AppError.WrongPassword, wrong.exceptionOrNull())
        server.on("PUT /me/password", 204)
        app.changePassword("old password", "a much better one")
        assertEquals(
            """{"currentPassword":"old password","newPassword":"a much better one"}""",
            server.called("PUT /me/password").last().body,
        )
        assertEquals("t1", store.token)
    }

    @Test
    fun onlyAnAdminBrowsingIsOfferedTheAdminConsole() = runBlocking {
        val admin = Json.account(1, "Helga").replace(""""isAdmin":false""", """"isAdmin":true""")
        val me = Json.me(1, admin, Json.account(2, "Joan", kind = "kid"))
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":$me}""")
        server.on("POST /profiles/1/unlock", 204)
        server.on("POST /profiles/2/unlock", 204)
        app.signIn("https://media.example.com", "helga@example.com", "pw")
        assertNull(app.adminUrl)
        app.choose(app.profiles.first())
        assertEquals("https://media.example.com/admin/", app.adminUrl)
        app.choose(app.profiles.last())
        assertNull(app.adminUrl)
    }
}
