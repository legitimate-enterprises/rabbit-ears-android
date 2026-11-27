package enterprises.legitimate.rabbitears.kit

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferTest {
    /** The client the app hands downloads keeps a disk cache for artwork, which a file skips. */
    @Test
    fun aDownloadIsNotCopiedIntoTheHttpCache() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val media = ByteArray(4096) { it.toByte() }
        server.createContext("/file") { exchange ->
            exchange.responseHeaders.add("ETag", "\"e1\"")
            exchange.responseHeaders.add("Last-Modified", "Mon, 01 Jan 2024 00:00:00 GMT")
            exchange.sendResponseHeaders(200, media.size.toLong())
            exchange.responseBody.use { it.write(media) }
        }
        server.start()
        try {
            val http = Api.cached(Files.createTempDirectory("http").toFile())
            val into = File(Files.createTempDirectory("media").toFile(), "1.mp4")
            HttpTransfer { http }
                .fetch("http://127.0.0.1:${server.address.port}/file", into, null) { _, _ -> }
            assertEquals(media.size.toLong(), into.length())
            assertEquals(0L, http.cache!!.size())
        } finally {
            server.stop(0)
        }
    }
}
