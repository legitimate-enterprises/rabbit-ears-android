package enterprises.legitimate.rabbitears.kit

import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsTest {
    private val server = FakeServer()
    private val model = FriendsModel(server.api(), "1", adult = true)

    private fun incoming(id: String, name: String, at: String = Json.T) =
        """{"id":"$id","fromProfileId":"7$id","fromName":"$name","fromColor":"#2E6B5B",""" +
            """"createdAt":"$at","expiresAt":"2026-02-01T00:00:00Z"}"""

    private fun outgoing(id: String, email: String) =
        """{"id":"$id","email":"$email","createdAt":"2026-01-02T00:00:00Z",""" +
            """"expiresAt":"2026-02-01T00:00:00Z"}"""

    private fun requests(
        incoming: List<String> = emptyList(),
        outgoing: List<String> = emptyList(),
    ) =
        server.on(
            "GET /friend-requests",
            body =
                """{"incoming":[${incoming.joinToString(",")}],""" +
                    """"outgoing":[${outgoing.joinToString(",")}]}""",
        )

    private fun friends(vararg accounts: String) {
        server.on("GET /friends", body = Json.page(*accounts))
        server.on("GET /profiles/1/share-candidates", body = """{"items":[]}""")
    }

    @Test
    fun anAdultSeesTheirFriendsByHouseholdAndWhatTheyReceivedAndSent() = runBlocking {
        server.respond = {
            if (it.url.encodedPath.endsWith("/friends"))
                200 to
                    if (it.url.queryParameter("offset") == "1")
                        """{"items":[${Json.account("31", "Mabel")}]}"""
                    else """{"items":[${Json.account("30", "Hal")}],"nextOffset":1}"""
            else null
        }
        server.on(
            "GET /profiles/1/share-candidates",
            body =
                """{"items":[{"profileId":"30","name":"Hal","kind":"adult",""" +
                    """"householdName":"Hal's place","householdId":"9","color":"#2E6B5B"}]}""",
        )
        requests(listOf(incoming("5", "Ivy")), listOf(outgoing("6", "dot@example.com")))
        model.load()
        assertNull(model.error)
        assertEquals(listOf("30", "31"), model.friends.map { it.id })
        assertEquals("Hal's place", model.household("30"))
        assertNull(model.household("31"))
        assertEquals(listOf("Ivy"), model.received.map { it.fromName })
        assertEquals(listOf("dot@example.com"), model.sent.map { it.email })
        assertEquals(1, model.badge)
    }

    @Test
    fun aKidAsksForNothingAndHasNothingToShow() = runBlocking {
        val kid = FriendsModel(server.api(), "7", adult = false)
        kid.load()
        assertTrue(server.calls.isEmpty())
        assertTrue(kid.friends.isEmpty() && kid.received.isEmpty() && kid.sent.isEmpty())
        assertEquals(0, kid.badge)
    }

    @Test
    fun everySendTheServerTakesHasTheSameAnswerWhoeverHoldsTheEmail() = runBlocking {
        friends()
        requests(outgoing = listOf(outgoing("6", "dot@example.com")))
        server.on("POST /friend-requests", 202, outgoing("6", "dot@example.com"))
        assertNull(model.send("  dot@example.com "))
        // The server answers a repeat with the request it already has.
        assertNull(model.send("Dot@Example.com"))
        val sent = server.called("POST /friend-requests")
        assertTrue(sent[0].body.contains(""""email":"dot@example.com""""))
        assertEquals(2, sent.size)
        assertEquals(1, model.sent.size)
        assertEquals(
            "If they're on this server, they'll get your friend request.",
            FriendsModel.SENT,
        )
    }

    @Test
    fun aMalformedEmailAndTooManySendsSaySoUnderTheField() = runBlocking {
        server.error("POST /friend-requests", 400, "bad_request", "email is not an email")
        assertEquals("Enter a valid email address.", model.send("dot@example.com"))
        server.error("POST /friend-requests", 429, "too_many_requests")
        assertEquals(
            "You've sent a lot of requests today. Try again later.",
            model.send("dot@example.com"),
        )
        assertTrue(server.called("GET /friend-requests").isEmpty())
    }

    @Test
    fun onlyAnAddressShapedLikeAnEmailCanBeSent() {
        assertTrue(FriendsModel.isEmail(" dot@example.com "))
        listOf("", "dot", "dot@", "@example.com", "dot@example", "d ot@example.com").forEach {
            assertFalse(it, FriendsModel.isEmail(it))
        }
    }

    @Test
    fun acceptingMakesThemAFriendAndOneWhoCanNoLongerBeAddedSaysSo() = runBlocking {
        requests(listOf(incoming("5", "Ivy"), incoming("8", "Rex")))
        friends()
        model.load()
        server.on("POST /friend-requests/5/accept", 204)
        requests(listOf(incoming("8", "Rex")))
        friends(Json.account("75", "Ivy"))
        assertNull(model.accept(model.received[0]))
        assertEquals(listOf("75"), model.friends.map { it.id })
        assertEquals(listOf("Rex"), model.received.map { it.fromName })

        server.error("POST /friend-requests/8/accept", 409, "conflict")
        assertEquals("This person can no longer be added.", model.accept(model.received[0]))
        assertTrue(model.received.isEmpty())
    }

    @Test
    fun aRequestGoneWhenAcceptedLeavesQuietly() = runBlocking {
        requests(listOf(incoming("5", "Ivy")))
        friends()
        model.load()
        server.error("POST /friend-requests/5/accept", 404, "not_found")
        requests()
        assertNull(model.accept(model.received[0]))
        assertTrue(model.received.isEmpty())
        assertNull(model.error)
    }

    @Test
    fun aDeclineTakesTheRowAwayBeforeTheServerAnswers() = runBlocking {
        requests(listOf(incoming("5", "Ivy")))
        friends()
        model.load()
        val answer = CountDownLatch(1)
        var seen: Int? = null
        server.before = {
            if (it.startsWith("DELETE")) {
                seen = model.received.size
                answer.await()
            }
        }
        server.error("DELETE /friend-requests/5", 404, "not_found")
        val decline = launch(Dispatchers.IO) { model.decline(model.received[0]) }
        eventually { seen != null }
        requests()
        answer.countDown()
        decline.join()
        assertEquals(0, seen)
        assertTrue(model.received.isEmpty())
        assertNull(model.error)
    }

    @Test
    fun aDeclineThatNeverReachedTheServerPutsTheRowBack() = runBlocking {
        requests(listOf(incoming("5", "Ivy")))
        friends()
        model.load()
        server.error("DELETE /friend-requests/5", 500, "internal", "Database is locked.")
        model.decline(model.received[0])
        assertEquals(listOf("5"), model.received.map { it.id })
        assertEquals("Database is locked.", model.error)
    }

    @Test
    fun aWithdrawnRequestAndARemovedFriendGoWhetherOrNotTheServerStillHadThem() = runBlocking {
        requests(outgoing = listOf(outgoing("6", "dot@example.com")))
        friends(Json.account("30", "Hal"))
        model.load()
        server.on("DELETE /friend-requests/6", 204)
        server.error("DELETE /friends/30", 404, "not_found")
        requests()
        friends()
        val hal = model.friends[0]
        model.withdraw(model.sent[0])
        model.remove(hal)
        assertTrue(model.sent.isEmpty() && model.friends.isEmpty())
        assertEquals(1, server.called("DELETE /friend-requests/6").size)
        assertEquals(1, server.called("DELETE /friends/30").size)
        assertNull(model.error)
    }

    @Test
    fun aSentRowSaysOnlyWhenItWasSentAndWhenItExpires() = runBlocking {
        requests(outgoing = listOf(outgoing("6", "dot@example.com")))
        friends()
        model.load()
        assertEquals(
            "Sent Jan 2, 2026 · Expires Feb 1, 2026",
            FriendsModel.sentLine(model.sent[0], ZoneOffset.UTC, Locale.US),
        )
    }
}
