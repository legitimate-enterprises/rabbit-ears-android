package enterprises.legitimate.rabbitears.kit

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The faked server's side of a local check, which confirms at [LAN] alone. */
object LocalChecks {
    const val LAN = "http://10.0.0.2:8477"
    private val key = ByteArray(32) { it.toByte() }

    /** The proof either side owes for [nonce], as server/internal/service/local.go computes it. */
    fun proof(side: String, nonce: String): String =
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal("$side:$nonce".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun nonce(body: String) = Regex("\"nonce\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    /**
     * Opens checks naming [addresses]; [reply] makes the proof of a nonce, as the real one does.
     */
    fun serve(
        server: FakeServer,
        addresses: List<String> = listOf("http://192.168.7.7:8477", LAN),
        reply: (String) -> String = { proof("server", it) },
    ) {
        val list = addresses.joinToString(",") { "\"$it\"" }
        server.respond = { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/v2/local-checks" ->
                    201 to
                        """{"id":"c1","key":"${Base64.getEncoder().encodeToString(key)}",""" +
                            """"addresses":[$list],"expiresAt":"2026-01-01T00:01:00Z"}"""
                path == "/api/v2/local-checks/c1/confirmation" &&
                    "${request.url.scheme}://${request.url.host}:${request.url.port}" == LAN -> {
                    val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    200 to """{"proof":"${reply(nonce(body) ?: "")}"}"""
                }
                path.endsWith("/confirmation") -> throw java.io.IOException("no route to host")
                else -> null
            }
        }
    }
}

class HomeLinkTest {
    private val server = FakeServer()
    private val api = server.api()
    private val endpoint = api.endpoint
    private val stream = "/api/v2/streams/k/0/master.m3u8"

    init {
        LocalChecks.serve(server)
    }

    private suspend fun check(
        path: PathFacts = PathFacts(),
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) = endpoint.confirmHome(api, path, force, nowMs)

    private val checks
        get() = server.called("POST /local-checks").size

    private val confirmations
        get() = server.called("POST /local-checks/c1/confirmation")

    @Test
    fun theFakedServerProvesAsTheRealOneDoes() {
        assertEquals(
            "4cb75bc399461f3f753c26613c4255dc32359753fb971f32dda89dad0b5fd00a",
            LocalChecks.proof("client", "0123456789abcdef"),
        )
        assertEquals(
            "07a81534f7cc202eb8ecf6676bae81d72c530387440efe1ec8f095e156985ed5",
            LocalChecks.proof("server", "0123456789abcdef"),
        )
    }

    @Test
    fun aConfirmedCheckPutsAPublicEndpointAtHome() = runBlocking {
        assertFalse(endpoint.atHome)
        assertFalse(Snapshot.network(PathFacts(), endpoint, QualityChoice.Auto).discoveredLocally)
        check()
        assertTrue(endpoint.atHome)
        assertFalse(endpoint.isLocalNetwork)
        assertTrue(Snapshot.network(PathFacts(), endpoint, QualityChoice.Auto).discoveredLocally)
        assertEquals("${LocalChecks.LAN}$stream", endpoint.resolveStream(stream))
        assertEquals("http://media.example.com:8477$stream", endpoint.resolve(stream))
        assertEquals("Bearer tok", server.called("POST /local-checks").single().auth)
        val sent = confirmations.last()
        assertNull("the token never crosses the local network", sent.auth)
        val nonce = LocalChecks.nonce(sent.body)!!
        assertTrue(nonce, nonce.length in 16..64)
        assertTrue(sent.body.contains(LocalChecks.proof("client", nonce)))
    }

    @Test
    fun aReplyThatDoesNotProveTheKeyIsNotTrusted() = runBlocking {
        val replies =
            listOf<(String) -> String>(
                { LocalChecks.proof("client", it) },
                { LocalChecks.proof("server", it).dropLast(2) },
                { "not hex" },
            )
        for (reply in replies) {
            LocalChecks.serve(server, reply = reply)
            check(force = true)
            assertFalse(endpoint.atHome)
        }
    }

    @Test
    fun withNoAddressAnsweringTheDeviceIsAway() = runBlocking {
        LocalChecks.serve(server, listOf("http://192.168.7.7:8477"))
        check(force = true)
        assertFalse(endpoint.atHome)
        LocalChecks.serve(server, emptyList())
        check(force = true)
        assertFalse(endpoint.atHome)
        server.respond = { null }
        check(force = true)
        assertFalse("a server too old to open a check", endpoint.atHome)
    }

    @Test
    fun oneAddressThatNeverAnswersDoesNotHoldUpAnotherNorIsWaitedOnPastTheLimit() = runBlocking {
        val held = server.respond
        server.respond = { request ->
            if (request.url.host == "192.168.7.7") Thread.sleep(10_000)
            held(request)
        }
        var began = System.nanoTime()
        check()
        assertTrue(endpoint.atHome)
        assertTrue(System.nanoTime() - began < 1_500_000_000)

        server.respond = { request ->
            if (request.url.encodedPath.endsWith("/confirmation")) Thread.sleep(10_000)
            held(request)
        }
        began = System.nanoTime()
        check(force = true)
        assertFalse(endpoint.atHome)
        assertTrue(System.nanoTime() - began < 5_000_000_000)
    }

    @Test
    fun aStreamHasMovedOnceACheckFindsAnotherAddressThanItWasResolvedAt() = runBlocking {
        check()
        endpoint.resolveStream(stream)
        assertFalse(endpoint.home.moved)
        LocalChecks.serve(server, emptyList())
        check(force = true)
        assertTrue(endpoint.home.moved)
        assertEquals("http://media.example.com:8477$stream", endpoint.resolveStream(stream))
        assertFalse(endpoint.home.moved)
    }

    @Test
    fun aCheckStandsTenMinutesOnItsPathUnlessForced() = runBlocking {
        val began = 1_000_000L
        val cellular = PathFacts(metered = true, cellular = true)
        check(nowMs = began)
        check(nowMs = began + 599_000)
        assertEquals(1, checks)
        check(cellular, nowMs = began + 600_000)
        assertEquals("a new path is when home is reached or left", 2, checks)
        check(cellular, nowMs = began + 1_199_000)
        assertEquals(2, checks)
        check(cellular, nowMs = began + 1_201_000)
        assertEquals(3, checks)
        check(cellular, force = true, nowMs = began + 1_202_000)
        assertEquals(4, checks)
        assertTrue(endpoint.atHome)
        val nonces = confirmations.mapNotNull { LocalChecks.nonce(it.body) }
        assertEquals("a new nonce for each attempt", nonces.size, nonces.toSet().size)
    }

    @Test
    fun aCheckThatFoundNothingStandsAsLong() = runBlocking {
        LocalChecks.serve(server, emptyList())
        check(nowMs = 0)
        check(nowMs = 599_000)
        assertEquals("away from home a start waits on dead addresses once", 1, checks)
        check(nowMs = 601_000)
        assertEquals(2, checks)
    }

    @Test
    fun aLocalEndpointAsksNothing() = runBlocking {
        val local = ServerEndpoint.parse("192.168.1.9")!!
        local.confirmHome(Api(local, "tok", server.http), PathFacts(), force = true)
        assertTrue(server.calls.isEmpty())
        assertTrue(local.atHome)
        assertEquals("http://192.168.1.9:8477$stream", local.resolveStream(stream))
    }

    @Test
    fun endpointsAreEqualByAddressWhateverTheirHome() = runBlocking {
        val other = ServerEndpoint.parse("media.example.com")!!
        check()
        assertTrue(endpoint.atHome)
        assertFalse(other.atHome)
        assertEquals(endpoint, other)
        assertEquals(endpoint.hashCode(), other.hashCode())
    }
}
