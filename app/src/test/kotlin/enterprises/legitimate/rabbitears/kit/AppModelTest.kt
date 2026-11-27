package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.DownloadQuality
import enterprises.legitimate.rabbitears.api.models.DownloadTarget
import enterprises.legitimate.rabbitears.api.models.DownloadTitle
import enterprises.legitimate.rabbitears.api.models.Me
import enterprises.legitimate.rabbitears.kit.AppModel.Phase
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModelTest {
    private val server = FakeServer()
    private val store = MemoryCredentialStore()
    private val app = AppModel(store, "Pixel 9", server.http)

    @Test
    fun withoutAStoredSessionAsksToSignIn() = runBlocking {
        app.restore()
        assertEquals(Phase.SignedOut, app.phase)
    }

    @Test
    fun signsInStoresTheTokenAndAsksWhoIsWatching() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        app.signIn("192.168.1.20", " helga@example.com ", "pw")
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertEquals("http://192.168.1.20:8477", store.server)
        assertEquals("t1", store.token)
        val body = server.calls.first().body
        assertTrue(body, body.contains(""""email":"helga@example.com""""))
        assertTrue(body, body.contains(""""platform":"android""""))
        assertTrue(body, body.contains(""""deviceName":"Pixel 9""""))
    }

    @Test
    fun wrongPasswordSaysSo() = runBlocking {
        server.error("POST /auth/sign-in", 401, "unauthorized")
        val error = runCatching {
            app.signIn("192.168.1.20", "a@example.com", "x")
        }
            .exceptionOrNull()
        assertEquals(AppError.WrongCredentials.message, error?.message)
        assertEquals(Phase.Starting, app.phase)
        assertNull(store.token)
    }

    @Test
    fun aSignInWhoseTokenCannotBeKeptIsRefusedAndSignedOut() = runBlocking {
        val sealless =
            object : CredentialStore by store {
                override var token: String?
                    get() = null
                    set(_) {}
            }
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        server.on("POST /auth/sign-out", 204)
        val app = AppModel(sealless, "Pixel 9", server.http)
        val error = runCatching { app.signIn("192.168.1.20", "a@example.com", "pw") }
        assertEquals(
            "This device couldn't keep the sign-in. Try again.",
            error.exceptionOrNull()?.message,
        )
        assertEquals("Bearer t1", server.called("POST /auth/sign-out").single().auth)
        assertEquals(Phase.Starting, app.phase)
        assertNull(app.api)
    }

    @Test
    fun aBadAddressNeverReachesTheNetwork() = runBlocking {
        val error = runCatching {
            app.signIn("not a server", "a@example.com", "x")
        }
            .exceptionOrNull()
        assertEquals(AppError.BadServer.message, error?.message)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun plainHttpCarriesThePasswordOnlyOnTheLocalNetwork() = runBlocking {
        val error = runCatching {
            app.signIn("media.example.com", "a@example.com", "x")
        }
            .exceptionOrNull()
        assertEquals(AppError.InsecureServer.message, error?.message)
        assertTrue(server.calls.isEmpty())

        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        app.signIn("https://media.example.com", "a@example.com", "pw")
        assertEquals("https://media.example.com", store.server)
        app.signIn("192.168.1.20", "a@example.com", "pw")
        assertEquals("http://192.168.1.20:8477", store.server)
    }

    @Test
    fun aStoredPlainHttpServerAwayFromHomeGetsNoToken() = runBlocking {
        store.server = "http://media.example.com:8477"
        store.token = "t1"
        app.restore()
        assertEquals(Phase.SignedOut, app.phase)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun restoresAndKeepsTheSessionWhenTheServerIsAway() = runBlocking {
        store.server = "http://192.168.1.20:8477"
        store.token = "t1"
        server.on("GET /me", 503, "")
        app.restore()
        assertEquals(Phase.Unreachable, app.phase)
        assertEquals("t1", store.token)

        server.on("GET /me", body = Json.me())
        app.restore()
        assertEquals(Phase.Browsing, app.phase)
        assertEquals("Bearer t1", server.calls.last().auth)
    }

    @Test
    fun aServerOutOfReachKeepsTheDownloadsAndOneThatNoLongerKnowsTheDeviceDeletesThem() =
        runBlocking {
            val downloads = DownloadStore(Files.createTempDirectory("app").toFile())
            val dune =
                DownloadTitle(
                    1,
                    DownloadTitle.Kind.movie,
                    1,
                    available = true,
                    watched = false,
                    positionMs = 0,
                    runtimeMs = 1,
                )
            downloads.put(
                1,
                DownloadTarget(itemId = 1),
                "Dune",
                DownloadQuality.original,
                listOf(dune),
            )
            downloads.setTitle(1) { it.copy(state = TitleState.OnDevice, file = "1.mp4") }
            downloads.setMe(Json.me())
            val app = AppModel(store, "Pixel 9", server.http, downloads)
            store.server = "http://192.168.1.20:8477"
            store.token = "t1"
            server.before = { throw IOException("no route to host") }
            app.restore()
            assertTrue(app.offline)
            assertEquals("t1", store.token)
            assertEquals(TitleState.OnDevice, downloads.title(1)?.state)

            server.before = {}
            server.error("GET /me", 401, "unauthorized")
            app.restore()
            assertEquals(Phase.SignedOut, app.phase)
            assertNull(downloads.title(1))
        }

    @Test
    fun aRevokedTokenSignsOut() = runBlocking {
        store.server = "http://192.168.1.20:8477"
        store.token = "t1"
        server.error("GET /me", 401, "unauthorized")
        app.restore()
        assertEquals(Phase.SignedOut, app.phase)
        assertNull(store.token)
    }

    /** A proxy or auth layer in front of the server may answer 401 with a body of its own. */
    @Test
    fun restoringOrReconnectingPastA401TheServerDidNotSendKeepsTheSessionAndEveryDownload() =
        runBlocking {
            val downloads = downloaded()
            stored(Json.me())
            downloads.setMe(Json.me())
            server.on("GET /me", 401, "<html>401 Authorization Required</html>")
            val app = AppModel(store, "Pixel 9", server.http, downloads)
            app.restore()
            assertTrue(app.offline)
            assertEquals("t1", store.token)
            assertEquals(TitleState.OnDevice, downloads.title(1)?.state)

            app.reconnect()
            assertTrue(app.offline)
            assertEquals("t1", store.token)
            assertEquals(TitleState.OnDevice, downloads.title(1)?.state)

            val bare = AppModel(store, "Pixel 9", server.http)
            bare.restore()
            assertEquals(Phase.Unreachable, bare.phase)
            assertEquals("t1", store.token)
        }

    @Test
    fun whoIsWatchingListsJoinedMembersAndKnowsWhoMayAddKids() = runBlocking {
        val kid = Json.account(3, "Joan", kind = "kid")
        val pending = Json.account(4, "Gary", pending = true)
        server.on(
            "POST /auth/sign-in",
            201,
            """{"token":"t1","me":${Json.me(1, Json.account(1, "Helga"), kid, pending)}}""",
        )
        app.signIn("192.168.1.20", "a@example.com", "pw")
        assertEquals(listOf("Helga", "Joan"), app.profiles.map { it.name })
        assertTrue(app.canAddKid)
        assertEquals("Helga", app.householdName)
    }

    @Test
    fun onlyKidsOnAManualAllowListAreListManaged() = runBlocking {
        val ratings = Json.account(3, "Joan", kind = "kid")
        val listed = Json.account(4, "Eli", kind = "kid", filterMode = "allow_list")
        server.on(
            "POST /auth/sign-in",
            201,
            """{"token":"t1","me":${Json.me(1, Json.account(1, "Helga"), ratings, listed)}}""",
        )
        app.signIn("192.168.1.20", "a@example.com", "pw")
        assertEquals(listOf("Eli"), app.listKids.map { it.name })
    }

    @Test
    fun hideSetOnAnotherDeviceTakesEffectHereOnceTheProfileIsReadAgain() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        server.on("POST /me/lock", 204)
        server.on("POST /profiles/1/unlock", 204)
        server.on("GET /items", body = Json.page(Json.item(1, "movie", "Dune")))
        server.on("GET /profiles/1/watch-state", body = Json.page())
        app.signIn("192.168.1.20", "a@example.com", "pw")
        app.choose(app.profiles.single())
        val catalog =
            CatalogModel(app.api!!, 1, hideKidsContent = app.activeProfile?.hideKidsContent == true)
        catalog.load()
        assertFalse(catalog.hideKidsContent)

        server.on("GET /me", body = Json.me(1, Json.account(1, "Helga", hide = true)))
        app.reload()
        catalog.hideKids(app.activeProfile?.hideKidsContent == true)
        assertTrue(catalog.hideKidsContent)
        assertEquals(8, server.called("GET /items").size)

        server.on("GET /me", body = Json.me(1, Json.account(1, "Helga", hide = false)))
        app.reload()
        catalog.hideKids(app.activeProfile?.hideKidsContent == true)
        assertFalse(catalog.hideKidsContent)
        assertEquals(12, server.called("GET /items").size)
    }

    @Test
    fun preferencesAreSavedAndTheProfileIsReadAgain() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        server.on("POST /me/lock", 204)
        server.on("POST /profiles/1/unlock", 204)
        app.signIn("192.168.1.20", "a@example.com", "pw")
        app.choose(app.profiles.single())

        val helga = Json.account(1, "Helga", color = "#2E6B5B")
        server.on("PUT /profiles/1/preferences", body = helga)
        server.on("GET /me", body = Json.me(1, helga))
        app.setPreferences(app.activeProfile!!, color = "#2E6B5B", hideKidsContent = true)
        val body = server.called("PUT /profiles/1/preferences").single().body
        assertTrue(
            body,
            body.contains(""""color":"#2E6B5B"""") && body.contains(""""hideKidsContent":true"""),
        )
        assertEquals("#2E6B5B", app.activeProfile?.color)
        assertEquals("#2E6B5B", app.profiles.single().color)
        app.setPreferences(app.activeProfile!!, autoplay = false)
        assertTrue(
            server
                .called("PUT /profiles/1/preferences")
                .last()
                .body
                .contains(""""autoplay":false"""),
        )

        server.error("PUT /profiles/1/preferences", 400, "bad_request", "Pick one of the colors.")
        val error = runCatching {
            app.setPreferences(app.activeProfile!!, color = "#000000")
        }
            .exceptionOrNull()
        assertEquals("Pick one of the colors.", error?.message)
    }

    @Test
    fun whoIsWatchingLocksTheDeviceBeforeItShows() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        server.on("POST /me/lock", 204)
        app.signIn("192.168.1.20", "a@example.com", "pw")
        assertEquals(
            listOf("POST /auth/sign-in", "POST /me/lock", "GET /health"),
            server.calls.map { it.route },
        )
        assertTrue(app.locked)

        server.on("POST /profiles/1/unlock", 204)
        app.choose(app.profiles.single())
        assertFalse(app.locked)
        var phaseAtLock: Phase? = null
        server.before = { if (it == "POST /me/lock") phaseAtLock = app.phase }
        app.switchProfile()
        assertEquals(Phase.Browsing, phaseAtLock)
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertTrue(app.locked)
    }

    @Test
    fun aLockThatFailsStillShowsWhoIsWatching() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        server.on("POST /me/lock", 503)
        app.signIn("192.168.1.20", "a@example.com", "pw")
        server.on("POST /profiles/1/unlock", 204)
        app.choose(app.profiles.single())
        app.switchProfile()
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertFalse(app.locked)
        assertTrue(app.canAddKid)
    }

    @Test
    fun aRestoredLockedDeviceLandsOnWhoIsWatchingWithoutLockingAgain() = runBlocking {
        store.server = "http://192.168.1.20:8477"
        store.token = "t1"
        store.setProfileMemory("http://192.168.1.20:8477/1", ProfileMemory(remember = false))
        server.on("GET /me", body = Json.me().dropLast(1) + ""","locked":true}""")
        app.restore()
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertTrue(app.locked)
        assertTrue(server.called("POST /me/lock").isEmpty())
    }

    @Test
    fun aDeviceLockedAnswerReturnsToWhoIsWatching() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        server.on("POST /me/lock", 204)
        app.signIn("192.168.1.20", "a@example.com", "pw")
        server.on("POST /profiles/1/unlock", 204)
        app.choose(app.profiles.single())

        server.error("GET /libraries", 403, "device_locked")
        runCatching { app.signedIn().call { libraries.listLibraries() } }
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertNull(app.activeProfile)
        assertTrue(app.locked)
    }

    @Test
    fun addKidWhileLockedUnlocksTheActiveAdultWithoutLeavingWhoIsWatching() = runBlocking {
        val gary = Json.account(2, "Gary", hasPin = true)
        store.server = "http://192.168.1.20:8477"
        store.token = "t1"
        server.on(
            "GET /me",
            body = Json.me(2, Json.account(1, "Helga"), gary).dropLast(1) + ""","locked":true}""",
        )
        app.restore()
        assertTrue(app.canAddKid)
        assertTrue(app.locked)

        server.on("POST /profiles/2/unlock", 204)
        app.unlock(app.profiles.first { it.id == 2L }, "1234")
        assertFalse(app.locked)
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertTrue(server.calls.last().body.contains(""""pin":"1234""""))
    }

    @Test
    fun choosingAProfileChecksThePin() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        app.signIn("192.168.1.20", "a@example.com", "pw")
        val helga = app.profiles.single()

        server.error("POST /profiles/1/unlock", 403, "forbidden")
        val wrong = runCatching { app.choose(helga, "1111") }.exceptionOrNull()
        assertEquals(AppError.WrongPin.message, wrong?.message)
        assertEquals(Phase.ChoosingProfile, app.phase)

        server.error("POST /profiles/1/unlock", 403, "pin_locked")
        assertEquals(
            AppError.PinLocked.message,
            runCatching { app.choose(helga, "1") }.exceptionOrNull()?.message,
        )

        server.on("POST /profiles/1/unlock", 204)
        app.choose(helga, "1234")
        assertEquals(Phase.Browsing, app.phase)
        assertEquals(helga, app.activeProfile)
        assertTrue(server.calls.last().body.contains(""""pin":"1234""""))
    }

    @Test
    fun signOutForgetsTheTokenAtOnce() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        app.signIn("192.168.1.20", "a@example.com", "pw")
        server.on("POST /auth/sign-out", 204)
        app.signOut()
        assertEquals(Phase.SignedOut, app.phase)
        assertNull(store.token)
        assertNull(app.api)
        assertEquals("http://192.168.1.20:8477", app.lastServer)
        assertFalse(app.canAddKid)
    }

    private fun stored(me: String, memory: ProfileMemory = ProfileMemory()) {
        store.server = "http://192.168.1.20:8477"
        store.token = "t1"
        store.setProfileMemory("http://192.168.1.20:8477/1", memory)
        server.on("GET /me", body = me)
        server.on("POST /me/lock", 204)
    }

    private fun locked(me: String) = me.dropLast(1) + ""","locked":true}"""

    private val gary = Json.account(2, "Gary", hasPin = true)

    @Test
    fun aRememberedProfileContinuesAtLaunchWithoutLocking() = runBlocking {
        stored(Json.me())
        app.restore()
        assertEquals(Phase.Browsing, app.phase)
        assertEquals("Helga", app.activeProfile?.name)
        assertEquals(listOf("GET /me", "GET /health"), server.calls.map { it.route })
    }

    @Test
    fun aRememberedProfileWithoutAPinUnlocksADeviceLeftLocked() = runBlocking {
        stored(locked(Json.me()))
        server.on("POST /profiles/1/unlock", 204)
        app.restore()
        assertEquals(Phase.Browsing, app.phase)
        assertFalse(app.locked)
        assertTrue(server.called("POST /me/lock").isEmpty())
    }

    @Test
    fun skipPinContinuesAsAPinProfileTheDeviceStillHas() = runBlocking {
        stored(Json.me(2, Json.account(1, "Helga"), gary))
        app.restore()
        assertEquals(Phase.Browsing, app.phase)
        assertEquals("Gary", app.activeProfile?.name)
        assertNull(app.pinFor)
    }

    @Test
    fun withoutSkipPinLaunchLocksAndAsksTheRememberedProfilesPin() = runBlocking {
        stored(Json.me(2, Json.account(1, "Helga"), gary), ProfileMemory(skipPin = false))
        app.restore()
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertTrue(app.locked)
        assertEquals("Gary", app.pinFor?.name)
        assertEquals(1, server.called("POST /me/lock").size)
    }

    @Test
    fun aPinProfileOnALockedDeviceAsksItsPinEvenWithSkipPin() = runBlocking {
        stored(locked(Json.me(2, Json.account(1, "Helga"), gary)))
        app.restore()
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertEquals("Gary", app.pinFor?.name)
        assertTrue(server.called("POST /profiles/2/unlock").isEmpty())
    }

    @Test
    fun rememberOffShowsWhoIsWatchingAtLaunch() = runBlocking {
        stored(Json.me(), ProfileMemory(remember = false))
        app.restore()
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertTrue(app.locked)
        assertNull(app.pinFor)
    }

    @Test
    fun aRememberedProfileThatIsGoneShowsWhoIsWatching() = runBlocking {
        stored(Json.me(5))
        app.restore()
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertTrue(app.locked)
        assertNull(app.pinFor)
    }

    @Test
    fun aRefusedUnlockAtLaunchShowsWhoIsWatching() = runBlocking {
        stored(locked(Json.me()))
        server.error("POST /profiles/1/unlock", 403, "forbidden")
        app.restore()
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertTrue(app.locked)
    }

    @Test
    fun signingInStillAsksWhoIsWatching() = runBlocking {
        stored(Json.me())
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        app.signIn("192.168.1.20", "a@example.com", "pw")
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertTrue(app.locked)
    }

    @Test
    fun profileMemoryIsOnByDefaultAndKeptPerAdult() = runBlocking {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        server.on("POST /me/lock", 204)
        app.signIn("192.168.1.20", "a@example.com", "pw")
        assertEquals(ProfileMemory(remember = true, skipPin = true), app.profileMemory)
        app.rememberProfiles(ProfileMemory(remember = true, skipPin = false))
        assertEquals(ProfileMemory(skipPin = false), app.profileMemory)
        assertEquals(
            ProfileMemory(skipPin = false),
            store.profileMemory("http://192.168.1.20:8477/1"),
        )
        assertEquals(ProfileMemory(), store.profileMemory("http://192.168.1.20:8477/2"))
    }

    private fun health(name: String?) =
        """{"status":"ok","version":"1.0.0","setupRequired":false""" +
            (name?.let { ""","serverName":"$it"""" } ?: "") +
            "}"

    @Test
    fun theProductNamesThePlaceUntilAServerNamesItself() = runBlocking {
        assertEquals("Rabbit Ears", app.placeName(""))
        assertEquals("Rabbit Ears", app.placeName("192.168.1.20"))
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        server.on("POST /me/lock", 204)
        server.on("GET /health", body = health("Home Media"))
        app.signIn("192.168.1.20", "a@example.com", "pw")
        assertEquals("Home Media", store.serverName)
        assertEquals("Home Media", app.placeName("192.168.1.20"))
        assertEquals("Rabbit Ears", app.placeName("192.168.1.30"))
    }

    @Test
    fun theStoredNameShowsAtLaunchAndIsReadAgainFromTheServer() = runBlocking {
        stored(Json.me())
        store.serverName = "Old Name"
        val app = AppModel(store, "Pixel 9", server.http)
        assertEquals("Old Name", app.placeName(app.lastServer))

        server.on("GET /health", 503)
        app.restore()
        assertEquals("Old Name", app.placeName(app.lastServer))

        server.on("GET /health", body = health("New Name"))
        app.restore()
        assertEquals("New Name", app.placeName(app.lastServer))
        assertEquals("New Name", store.serverName)
    }

    @Test
    fun anotherServerNeverWearsTheLastOnesName() = runBlocking {
        store.server = "http://192.168.1.20:8477"
        store.serverName = "Home Media"
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        server.on("POST /me/lock", 204)
        server.on("GET /health", 503)
        app.signIn("192.168.1.30", "a@example.com", "pw")
        assertNull(store.serverName)
        assertEquals("Rabbit Ears", app.placeName("192.168.1.30"))
    }

    @Test
    fun aBlankNameIsNeverAHeading() = runBlocking {
        stored(Json.me())
        server.on("GET /health", body = health("  "))
        app.restore()
        assertEquals("Rabbit Ears", app.placeName(app.lastServer))
    }

    private val mom = Json.account(1, "Helga", hasPin = true)
    private val joan = Json.account(3, "Joan", kind = "kid")

    /** A device with Dune downloaded, so a server out of reach browses offline. */
    private fun downloaded(): DownloadStore {
        val downloads = DownloadStore(Files.createTempDirectory("app").toFile())
        val dune =
            DownloadTitle(
                1,
                DownloadTitle.Kind.movie,
                1,
                available = true,
                watched = false,
                positionMs = 0,
                runtimeMs = 1,
            )
        downloads.put(1, DownloadTarget(itemId = 1), "Dune", DownloadQuality.original, listOf(dune))
        downloads.setTitle(1) { it.copy(state = TitleState.OnDevice, file = "1.mp4") }
        return downloads
    }

    /** A fresh launch, as after the process dies, with the server out of reach. */
    private suspend fun relaunchOffline(downloads: DownloadStore): AppModel {
        server.before = { throw IOException("no route to host") }
        return AppModel(store, "Pixel 9", server.http, downloads).also { it.restore() }
    }

    private fun snapshot(downloads: DownloadStore) =
        DownloadStore.json.decodeFromString(Me.serializer(), downloads.state.me!!)

    @Test
    fun aProfileTheHouseholdNoLongerListsLosesItsDownloadsWhenTheOwnersAnswerSaysSo() =
        runBlocking {
            val downloads = downloaded()
            fun title(id: Long) =
                DownloadTitle(
                    id,
                    DownloadTitle.Kind.movie,
                    1,
                    available = true,
                    watched = false,
                    positionMs = 0,
                    runtimeMs = 1,
                )
            downloads.put(
                2,
                DownloadTarget(itemId = 22),
                "B",
                DownloadQuality.original,
                listOf(title(22)),
            )
            downloads.put(
                3,
                DownloadTarget(itemId = 1),
                "Dune",
                DownloadQuality.original,
                listOf(title(1)),
            )
            downloads.put(
                3,
                DownloadTarget(itemId = 21),
                "A",
                DownloadQuality.original,
                listOf(title(21)),
            )
            downloads.setTitle(21) { it.copy(state = TitleState.OnDevice, file = "21.mp4") }
            val file =
                downloads.media("21.mp4").apply { parentFile!!.mkdirs() }.apply { writeText("x") }
            val gary = Json.account(2, "Gary")
            val app = AppModel(store, "Pixel 9", server.http, downloads)

            // Pending adults are listed to the owner alone, so another profile's answer leaves 3
            // be.
            stored(Json.me(2, Json.account(1, "Helga"), gary))
            app.restore()
            assertEquals(2, downloads.requests(3).size)

            stored(Json.me(1, Json.account(1, "Helga"), gary))
            app.restore()
            assertTrue(downloads.requests(3).isEmpty())
            assertNull(downloads.title(21))
            eventually { !file.exists() }
            assertEquals("profile 1 still holds it", TitleState.OnDevice, downloads.title(1)?.state)
            assertEquals(1, downloads.requests(1).size)
            assertEquals(1, downloads.requests(2).size)
        }

    @Test
    fun aKidWhoSwitchedFromAPinAdultNeverReopensAsTheAdultOffline() = runBlocking {
        val downloads = downloaded()
        stored(Json.me(1, mom, joan))
        server.on("POST /profiles/3/unlock", 204)
        val app = AppModel(store, "Pixel 9", server.http, downloads)
        app.restore()
        assertEquals("Helga", app.activeProfile?.name)
        app.switchProfile()
        app.choose(app.profiles.first { it.id == 3L })

        val after = relaunchOffline(downloads)
        assertTrue(after.offline)
        assertEquals(Phase.Browsing, after.phase)
        assertEquals("Joan", after.activeProfile?.name)
    }

    @Test
    fun aDeviceLeftAtWhoIsWatchingAsksAgainOffline() = runBlocking {
        val downloads = downloaded()
        stored(Json.me(1, mom, joan))
        val app = AppModel(store, "Pixel 9", server.http, downloads)
        app.restore()
        app.switchProfile()

        val after = relaunchOffline(downloads)
        assertTrue(after.offline)
        assertEquals(Phase.ChoosingProfile, after.phase)
        assertNull(after.activeProfile)
    }

    @Test
    fun aPinProfileOpensOfflineOnlyOnAnUnlockedDeviceWithSkipPin() = runBlocking {
        val downloads = downloaded()
        stored(Json.me(1, mom, joan))
        downloads.setMe(locked(Json.me(1, mom, joan)))
        assertEquals(Phase.ChoosingProfile, relaunchOffline(downloads).phase)

        downloads.setMe(Json.me(1, mom, joan))
        store.setProfileMemory("http://192.168.1.20:8477/1", ProfileMemory(skipPin = false))
        assertEquals(Phase.ChoosingProfile, relaunchOffline(downloads).phase)

        store.setProfileMemory("http://192.168.1.20:8477/1", ProfileMemory())
        val opened = relaunchOffline(downloads)
        assertEquals(Phase.Browsing, opened.phase)
        assertEquals("Helga", opened.activeProfile?.name)
    }

    @Test
    fun aProfileWithoutAPinOpensOfflineOnALockedDevice() = runBlocking {
        val downloads = downloaded()
        stored(Json.me(3, mom, joan))
        downloads.setMe(locked(Json.me(3, mom, joan)))
        val app = relaunchOffline(downloads)
        assertEquals(Phase.Browsing, app.phase)
        assertEquals("Joan", app.activeProfile?.name)
    }

    @Test
    fun switchingProfileOfflineKeepsTheDeviceAtWhoIsWatchingForTheNextLaunch() = runBlocking {
        val downloads = downloaded()
        stored(Json.me(1, mom, joan))
        downloads.setMe(Json.me(1, mom, joan))
        val app = relaunchOffline(downloads)
        assertEquals("Helga", app.activeProfile?.name)
        app.switchProfile()
        app.choose(app.profiles.first { it.id == 3L })

        assertEquals(Phase.ChoosingProfile, relaunchOffline(downloads).phase)
    }

    @Test
    fun theHouseholdKeptForOfflineFollowsLockAndUnlock() = runBlocking {
        val downloads = downloaded()
        stored(Json.me(1, mom, joan))
        val app = AppModel(store, "Pixel 9", server.http, downloads)
        app.restore()

        app.lock()
        assertEquals(true, snapshot(downloads).locked)

        server.on("POST /profiles/3/unlock", 204)
        app.unlock(app.profiles.first { it.id == 3L })
        assertEquals(3L, snapshot(downloads).activeProfileId)
        assertEquals(false, snapshot(downloads).locked)

        server.on("POST /me/lock", 503)
        app.lock()
        assertFalse(app.locked)
        assertEquals(true, snapshot(downloads).locked)

        app.unlock(app.profiles.first { it.id == 3L })
        server.error("GET /libraries", 403, "device_locked")
        runCatching { app.signedIn().call { libraries.listLibraries() } }
        assertEquals(true, snapshot(downloads).locked)
    }

    @Test
    fun switchingProfileForgetsThePinPrompt() = runBlocking {
        stored(Json.me(2, Json.account(1, "Helga"), gary), ProfileMemory(skipPin = false))
        app.restore()
        server.on("POST /profiles/2/unlock", 204)
        app.choose(app.pinFor!!, "1234")
        app.switchProfile()
        assertEquals(Phase.ChoosingProfile, app.phase)
        assertNull(app.pinFor)
    }
}
