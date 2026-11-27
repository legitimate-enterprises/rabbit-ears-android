package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.AccountDeletion
import enterprises.legitimate.rabbitears.api.models.AccountDeletion.Outcome
import enterprises.legitimate.rabbitears.kit.AppModel.Phase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionTest {
    private val server = FakeServer()
    private val store = MemoryCredentialStore()
    private val app = AppModel(store, "Pixel 9", server.http)

    private suspend fun signedIn() {
        server.on("POST /auth/sign-in", 201, """{"token":"t1","me":${Json.me()}}""")
        app.signIn("192.168.1.20", "helga@example.com", "pw")
    }

    @Test
    fun theConfirmationSaysWhichWayTheHouseholdGoes() = runBlocking {
        signedIn()
        server.on(
            "GET /me/deletion",
            200,
            """{"outcome":"transfer","newOwnerName":"Bea","kidNames":[]}""",
        )
        val told = app.accountDeletion()
        assertTrue(told.allowed)
        assertTrue(
            told.summary,
            told.summary.endsWith(
                "cannot be undone. The household stays, with its kids, and Bea becomes its owner.",
            ),
        )
        assertTrue(
            AccountDeletion(Outcome.account, emptyList())
                .summary
                .endsWith("The household stays as it is."),
        )
        assertTrue(
            AccountDeletion(Outcome.household, emptyList())
                .summary
                .endsWith("so the household goes too."),
        )
        assertTrue(
            AccountDeletion(Outcome.household, listOf("Mia", "Leo", "Ada"))
                .summary
                .endsWith(
                    "the household goes too, with Mia, Leo and Ada and everything of theirs.",
                ),
        )
        val refused = AccountDeletion(Outcome.refused, emptyList())
        assertFalse(refused.allowed)
        assertTrue(refused.summary.contains("make another account an admin"))
        assertFalse(refused.summary.contains("cannot be undone"))
    }

    @Test
    fun deletingSendsThePasswordThenSignsThisDeviceOut() = runBlocking {
        signedIn()
        server.error("POST /me/deletion", 403, "forbidden")
        val wrong = runCatching { app.deleteAccount("nope") }.exceptionOrNull()
        assertEquals(AppError.WrongPassword.message, wrong?.message)
        assertEquals("t1", store.token)

        server.on("POST /me/deletion", 204)
        app.deleteAccount("the password")
        assertEquals(
            """{"password":"the password"}""",
            server.called("POST /me/deletion").last().body,
        )
        assertEquals(Phase.SignedOut, app.phase)
        assertNull(store.token)
    }

    /** Another device of an account deleted here hears 401 on its held poll and signs out. */
    @Test
    fun aFollowerTheServerNoLongerKnowsSaysSo() = runBlocking {
        for ((status, code, gone) in
            listOf(
                Triple(401, "unauthorized", true),
                Triple(403, "device_locked", false),
                Triple(404, "not_found", false),
            )) {
            server.error("GET /events", status, code)
            assertEquals(code, gone, LiveFeed().follow(server.api()) {})
        }
    }
}
