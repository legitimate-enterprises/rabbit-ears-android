package enterprises.legitimate.rabbitears.kit

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkCacheTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val hits = AtomicInteger()
    private val dir = Files.createTempDirectory("artwork-cache").toFile()

    @After
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun serve(cacheControl: String) {
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.responseHeaders.add("Cache-Control", cacheControl)
            exchange.responseHeaders.add("ETag", "\"w500/a.jpg\"")
            exchange.responseHeaders.add("Artwork-Generation", "5")
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write("jpg".toByteArray()) }
        }
        server.start()
    }

    private fun api() =
        Api(ServerEndpoint("http://127.0.0.1:${server.address.port}"), "tok", Api.cached(dir))

    @Test
    fun anImageTheServerAllowsKeepingIsReadFromDiskTheSecondTime() {
        serve("private, max-age=31536000, immutable")
        val before = api()
        val first = before.bytes("/api/v2/items/1/artwork/poster?v=5")
        // A new Api, as after a restart, shares only the cache directory.
        before.http.cache!!.close()
        val again = api().bytes("/api/v2/items/1/artwork/poster?v=5")
        assertEquals("jpg", String(first.body!!))
        assertEquals("jpg", String(again.body!!))
        assertEquals(1, hits.get())
    }

    @Test
    fun anImageTheServerWantsRevalidatedIsAskedForAgain() {
        serve("private, max-age=0")
        val api = api()
        api.bytes("/api/v2/items/1/artwork/poster")
        api.bytes("/api/v2/items/1/artwork/poster")
        assertEquals(2, hits.get())
    }

    @Test
    fun theGenerationAnyResponseNamesIsKept() {
        serve("private, max-age=0")
        val api = api()
        assertNull(api.artworkGeneration)
        api.bytes("/api/v2/health")
        assertEquals(5L, api.artworkGeneration)
        assertNotNull(api.http.cache)
    }
}
