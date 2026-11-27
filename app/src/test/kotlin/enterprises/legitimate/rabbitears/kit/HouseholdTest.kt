package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.CeilingSuggestion
import enterprises.legitimate.rabbitears.api.models.MovieRating
import enterprises.legitimate.rabbitears.api.models.TvRating
import enterprises.legitimate.rabbitears.kit.MemberAction.EditKid
import enterprises.legitimate.rabbitears.kit.MemberAction.MakeAdult
import enterprises.legitimate.rabbitears.kit.MemberAction.Remove
import enterprises.legitimate.rabbitears.kit.MemberAction.ResendCode
import enterprises.legitimate.rabbitears.kit.MemberAction.SetPin
import enterprises.legitimate.rabbitears.kit.MemberAction.Share
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdTest {
    private val server = FakeServer()
    private val app = AppModel(MemoryCredentialStore(), "Pixel 9", server.http)
    private val model = HouseholdModel(app)

    private val owner = account(Json.account(1, "Helga"))
    private val bea = account(Json.account(4, "Bea"))
    private val kid = account(Json.account(2, "Joan", kind = "kid"))
    private val invited = account(pending(3, "Cy"))

    private val invite =
        """{"account":${pending(3, "Cy")},"joinCode":{"id":5,"code":"7KQ4-MNP2",""" +
            """"link":"/join/7KQ4-MNP2","accountId":3,"expiresAt":"${Json.T}",""" +
            """"createdAt":"${Json.T}"}}"""

    private fun pending(id: Long, name: String, expires: String? = null) =
        Json.account(id, name)
            .replace(
                """"pending":false""",
                """"pending":true""" + (expires?.let { ""","inviteExpiresAt":"$it"""" } ?: ""),
            )

    private fun account(json: String) =
        Serializer.kotlinxSerializationJson.decodeFromString(Account.serializer(), json)

    private suspend fun signedIn(active: Long = 1) {
        val me = Json.me(active, Json.account(1, "Helga"), Json.account(2, "Joan", kind = "kid"))
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":$me}""")
        server.on("GET /me", body = me)
        server.on(
            "GET /profiles",
            body =
                Json.page(
                    Json.account(1, "Helga"),
                    Json.account(2, "Joan", kind = "kid"),
                    pending(3, "Cy", "2026-01-08T12:00:00Z"),
                    Json.account(5, "Gone").dropLast(1) + ""","revokedAt":"${Json.T}"}""",
                ),
        )
        server.on("GET /household/offers", body = """{"incoming":[],"outgoing":[]}""")
        app.signIn("192.168.1.20", "helga@example.com", "pw")
    }

    private fun offer(id: Long, profile: String, kind: String, from: String, to: String) =
        """{"id":$id,"profileId":${id + 10},"profileName":"$profile","profileKind":"$kind",""" +
            """"householdId":1,"householdName":"Helga","fromProfileId":1,"fromName":"$from",""" +
            """"toProfileId":6,"toName":"$to","createdAt":"${Json.T}"}"""

    /** [json] in [households] households, and only visiting this one when [present]. */
    private fun placed(json: String, households: Int, present: Boolean = false) =
        account(json.dropLast(1) + ""","households":$households,"present":$present}""")

    private val visitor = placed(Json.account(6, "Jo"), 2, present = true)

    @Test
    fun theOwnerManagesEveryoneButThemselves() {
        assertEquals(setOf(SetPin, Share), MemberAction.allowed(owner, owner, 1))
        assertEquals(
            setOf(EditKid, SetPin, Remove, MakeAdult, Share),
            MemberAction.allowed(kid, owner, 1),
        )
        assertEquals(setOf(Remove, Share), MemberAction.allowed(bea, owner, 1))
        assertEquals(setOf(ResendCode, Remove), MemberAction.allowed(invited, owner, 1))
        assertEquals(setOf(Remove), MemberAction.allowed(visitor, owner, 1))
    }

    @Test
    fun anotherAdultLooksAfterKidsAndTheirOwnPinAndSharesAnyoneOfTheHousehold() {
        assertEquals(setOf(EditKid, SetPin, Remove, Share), MemberAction.allowed(kid, bea, 1))
        assertEquals(setOf(SetPin, Share), MemberAction.allowed(bea, bea, 1))
        assertEquals(setOf(Share), MemberAction.allowed(owner, bea, 1))
        assertEquals(emptySet<MemberAction>(), MemberAction.allowed(invited, bea, 1))
        assertEquals(emptySet<MemberAction>(), MemberAction.allowed(visitor, bea, 1))
    }

    @Test
    fun anAdultOnlyVisitingManagesNothingAndMayLeave() = runBlocking {
        assertEquals(emptySet<MemberAction>(), MemberAction.allowed(kid, visitor, 1))
        val jo = Json.account(6, "Jo").dropLast(1) + ""","households":2,"present":true}"""
        val me = Json.me(6, Json.account(1, "Helga"), jo)
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":$me}""")
        server.on("GET /me", body = me)
        server.on("GET /profiles", body = Json.page(Json.account(1, "Helga"), jo))
        server.on("POST /me/lock", 204)
        app.signIn("192.168.1.20", "jo@example.com", "pw")
        assertTrue(model.visiting)
        assertFalse(app.canAddKid)
        model.load()
        assertEquals(null, model.error)
        assertTrue(server.called("GET /household/offers").isEmpty())

        server.on("DELETE /profiles/6", 204)
        model.leave()
        assertEquals(1, server.called("DELETE /profiles/6").size)
        assertEquals(AppModel.Phase.ChoosingProfile, app.phase)
        // A device of the visitor's own there is signed out by the server.
        server.error("GET /me", 401, "unauthorized")
        model.leave()
        assertEquals(AppModel.Phase.SignedOut, app.phase)
    }

    @Test
    fun aSharedProfileSaysSoAndOnlyLeavesThisHousehold() {
        val shared = placed(Json.account(2, "Joan", kind = "kid"), 2)
        assertEquals("In 2 households", shared.placeLine())
        assertEquals("Adult", visitor.memberLine(1))
        assertEquals("Visiting from another household", visitor.placeLine())
        assertEquals(null, kid.placeLine())
        assertEquals(null, placed(Json.account(2, "Joan", kind = "kid"), 1).placeLine())

        assertTrue(shared.inOthers && visitor.inOthers && !kid.inOthers)
        assertEquals(
            "Joan stays in their other household, and nothing of theirs is deleted.",
            shared.removal,
        )
        assertEquals(
            "Joan stays in their other households, and nothing of theirs is deleted.",
            placed(Json.account(2, "Joan", kind = "kid"), 3).removal,
        )
        assertEquals("Their account and watch history are deleted.", kid.removal)
        assertEquals("Their code stops working.", invited.removal)
        assertEquals("Their devices are signed out.", bea.removal)
    }

    @Test
    fun aProfileIsOfferedToAConnectedAdult() = runBlocking {
        signedIn()
        server.on(
            "GET /household/linked-adults",
            body = Json.page(Json.account(6, "Marcus"), Json.account(7, "Ines")),
        )
        assertEquals(listOf("Marcus", "Ines"), model.connectedAdults().map { it.name })
        server.on("POST /household/offers", 201, offer(9, "Joan", "kid", "Helga", "Marcus"))
        model.offer(kid, model.connectedAdults().first())
        assertEquals(
            """{"profileId":2,"toProfileId":6}""",
            server.called("POST /household/offers").single().body,
        )
        assertEquals(1, server.called("GET /household/offers").size)
        server.error("POST /household/offers", 409, "conflict", "Already offered.")
        val error = runCatching { model.offer(kid, model.connectedAdults().first()) }
        assertEquals("Already offered.", error.exceptionOrNull()?.message)
    }

    @Test
    fun aKidMayDoNothing() {
        assertEquals(emptySet<MemberAction>(), MemberAction.allowed(kid, kid, 1))
        assertEquals(emptySet<MemberAction>(), MemberAction.allowed(owner, null, 1))
    }

    @Test
    fun memberLinesSayWhoEachIs() {
        assertEquals("Owner", owner.memberLine(1))
        assertEquals("Adult", bea.memberLine(1))
        assertEquals("Awaiting sign-in · code expired", invited.memberLine(1))
        val expiring = account(pending(3, "Cy", "2026-01-08T12:00:00Z"))
        assertTrue(expiring.memberLine(1).startsWith("Awaiting sign-in · code expires Jan "))
        val ceilings =
            account(
                Json.account(2, "Joan", kind = "kid").dropLast(1) +
                    ""","movieCeiling":"PG","tvCeiling":"TV-Y7"}""",
            )
        assertEquals("Kid · movies up to PG · TV up to TV-Y7", ceilings.memberLine(1))
        val listed = account(Json.account(2, "Joan", kind = "kid", filterMode = "allow_list"))
        assertEquals("Kid · only titles you choose", listed.memberLine(1))
    }

    @Test
    fun listsTheMembersStillInTheHousehold() = runBlocking {
        signedIn()
        model.load()
        assertEquals(null, model.error)
        assertEquals(listOf("Helga", "Joan", "Cy"), model.members.map { it.name })
        assertEquals(setOf(ResendCode, Remove), model.actions(model.members[2]))
    }

    @Test
    fun offersSayWhoSharesWhomAndAreAcceptedDeclinedOrWithdrawn() = runBlocking {
        signedIn()
        val incoming = offer(7, "Jason", "kid", "Marcus", "Helga")
        val outgoing = offer(8, "Mary", "kid", "Helga", "Marcus")
        server.on(
            "GET /household/offers",
            body = """{"incoming":[$incoming],"outgoing":[$outgoing]}""",
        )
        model.load()
        val (jason) = model.incoming
        val (mary) = model.outgoing
        assertEquals("Kid · Marcus offers to share Jason with your household", jason.line(true))
        assertEquals("Kid · Offered to Marcus, waiting", mary.line(false))

        server.on("POST /household/offers/7/accept", 204)
        server.on("DELETE /household/offers/7", 204)
        server.on("DELETE /household/offers/8", 204)
        model.accept(jason)
        model.dismiss(jason)
        model.dismiss(mary)
        assertEquals(1, server.called("POST /household/offers/7/accept").size)
        assertEquals(1, server.called("DELETE /household/offers/7").size)
        assertEquals(1, server.called("DELETE /household/offers/8").size)
        assertEquals(4, server.called("GET /household/offers").size)
        assertEquals(3, server.called("GET /me").size)
    }

    @Test
    fun aKidIsNeverAskedForOffers() = runBlocking {
        signedIn()
        server.on("POST /profiles/2/unlock", 204)
        app.choose(app.profiles.last())
        model.load()
        assertEquals(null, model.error)
        assertTrue(server.called("GET /household/offers").isEmpty())
    }

    @Test
    fun choosingAKidTakesAwayHouseholdPowers() = runBlocking {
        signedIn()
        assertTrue(app.isOwner)
        server.on("POST /profiles/2/unlock", 204)
        app.choose(app.profiles.last())
        assertEquals(false, app.isOwner)
        assertEquals(emptySet<MemberAction>(), model.actions(kid))
    }

    @Test
    fun aPinIsSetOrRemovedThenTheHouseholdIsReadAgain() = runBlocking {
        signedIn()
        server.on("PUT /profiles/2/pin", 204)
        server.on("DELETE /profiles/2/pin", 204)
        model.setPin(kid, "2468")
        assertEquals("""{"pin":"2468"}""", server.called("PUT /profiles/2/pin").single().body)
        model.setPin(kid, null)
        assertEquals(1, server.called("DELETE /profiles/2/pin").size)
        assertEquals(2, server.called("GET /profiles").size)
        assertEquals(2, server.called("GET /me").size)
    }

    @Test
    fun removingSaysWhatTheServerRefuses() = runBlocking {
        signedIn()
        server.on("DELETE /profiles/4", 204)
        model.remove(bea)
        assertEquals(1, server.called("DELETE /profiles/4").size)
        assertEquals(1, server.called("GET /me").size)
        server.error("DELETE /profiles/4", 409, "conflict", "Last admin.")
        val error = runCatching { model.remove(bea) }.exceptionOrNull()
        assertEquals("Last admin.", error?.message)
    }

    @Test
    fun resendAndChangeToAdultHandBackTheCode() = runBlocking {
        signedIn()
        server.on("POST /profiles/3/join-code", 201, invite)
        server.on("POST /profiles/2/make-adult", 201, invite)
        val resent = model.resendCode(invited)
        assertEquals("http://192.168.1.20:8477/join/7KQ4-MNP2", resent.url)
        val made = model.makeAdult(kid, "joan@example.com")
        assertEquals("7KQ4-MNP2", made.code)
        assertTrue(
            server.called("POST /profiles/2/make-adult").single().body,
            server
                .called("POST /profiles/2/make-adult")
                .single()
                .body
                .contains(""""email":"joan@example.com""""),
        )
        server.error("POST /profiles/2/make-adult", 409, "conflict")
        val error = runCatching { model.makeAdult(kid, "taken@example.com") }.exceptionOrNull()
        assertEquals(AppError.EmailTaken, error)
    }

    @Test
    fun editingAKidReplacesItAndChangesThePinOnlyWhenAsked() = runBlocking {
        signedIn()
        server.on("PUT /profiles/2", body = Json.account(2, "Joan", kind = "kid"))
        server.on("PUT /profiles/2/pin", 204)
        server.on("DELETE /profiles/2/pin", 204)
        val joan =
            account(
                Json.account(2, "Joan", kind = "kid", hasPin = true).dropLast(1) +
                    ""","birthday":"2017-08-19","movieCeiling":"G","tvCeiling":"TV-Y"}""",
            )
        val form = AddKidForm(LocalDate.of(2026, 11, 14), joan)
        assertEquals(LocalDate.of(2017, 8, 19), form.birthday)
        assertEquals(MovieRating.G, form.movieCeiling)
        assertTrue(form.requirePin)
        assertEquals(AddKidForm.PinChange.Keep, form.pinChange)
        assertTrue(form.errors.isEmpty())
        form.apply(CeilingSuggestion(9, MovieRating.PG, TvRating.TVMinusPG))
        assertEquals(MovieRating.G, form.movieCeiling)

        form.name = "Robbie"
        model.saveKid(joan, form)
        val put = server.called("PUT /profiles/2").single().body
        assertTrue(
            put,
            put.contains(""""name":"Robbie"""") && put.contains(""""movieCeiling":"G""""),
        )
        assertTrue(server.called("PUT /profiles/2/pin").isEmpty())
        form.requirePin = false
        model.saveKid(joan, form)
        assertEquals(1, server.called("DELETE /profiles/2/pin").size)
        form.requirePin = true
        form.pin = "2468"
        assertEquals("PINs don't match.", form.errors[AddKidForm.Field.Pin])
        form.pinConfirmation = "2468"
        model.saveKid(joan, form)
        assertEquals("""{"pin":"2468"}""", server.called("PUT /profiles/2/pin").single().body)
        assertEquals(3, server.called("GET /profiles").size)
    }

    @Test
    fun anAllowListAddsAndRemovesTitles() = runBlocking {
        signedIn()
        server.on("GET /profiles/2/allowed-items", body = """{"itemIds":[33,30]}""")
        server.on("GET /items/30", body = Json.item(30, "movie", "Toy Story"))
        server.error("GET /items/33", 404, "not_found")
        server.on(
            "GET /items",
            body =
                Json.page(Json.item(31, "movie", "Matrix"), Json.item(32, "episode", parent = 9)),
        )
        server.on("PUT /profiles/2/allowed-items/31", 204)
        server.on("DELETE /profiles/2/allowed-items/30", 204)
        assertEquals(listOf(30L), model.allowed(kid).map { it.id })
        assertEquals(listOf(31L), model.findTitles("mat").map { it.id })
        assertTrue(server.called("GET /items").last().query.orEmpty().contains("q=mat"))
        model.allow(kid, 31)
        model.disallow(kid, 30)
        assertEquals(1, server.called("PUT /profiles/2/allowed-items/31").size)
        assertEquals(1, server.called("DELETE /profiles/2/allowed-items/30").size)
    }
}
