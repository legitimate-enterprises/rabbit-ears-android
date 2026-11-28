package enterprises.legitimate.rabbitears.kit

import com.sun.net.httpserver.HttpServer
import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.YouTubeVideo
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtworkCacheTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val asked = Collections.synchronizedList(mutableListOf<String>())
    private val dir = Files.createTempDirectory("artwork-cache").toFile()

    @After
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun serve(cacheControl: String) {
        server.createContext("/") { exchange ->
            asked += exchange.requestURI.toString()
            exchange.responseHeaders.add("Cache-Control", cacheControl)
            exchange.responseHeaders.add("ETag", "\"w500/a.jpg\"")
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write("jpg".toByteArray()) }
        }
        server.start()
    }

    private fun api() =
        Api(ServerEndpoint("http://127.0.0.1:${server.address.port}"), "tok", Api.cached(dir))

    private fun item(posterUrl: String?) =
        Serializer.kotlinxSerializationJson.decodeFromString(
            Item.serializer(),
            Json.item("1", "movie").let {
                if (posterUrl == null) it
                else it.removeSuffix("}") + ""","posterUrl":"$posterUrl"}"""
            },
        )

    @Test
    fun anAddressIsTakenFromTheObjectsField() {
        val url = "/api/v2/artwork/w500/abc.jpg"
        assertEquals(url, ArtworkPath.item("1", item(url).posterUrl))
        assertEquals(url, ArtworkPath.item("1", url, "backdrop"))
        assertEquals(url, ArtworkPath.collection("1", url))
        assertEquals(url, ArtworkPath.person("1", url))
    }

    @Test
    fun withoutTheFieldTheIdAddressIsTakenWithNoGeneration() {
        assertEquals("/api/v2/items/1/artwork/poster", ArtworkPath.item("1", item(null).posterUrl))
        assertEquals("/api/v2/items/1/artwork/backdrop", ArtworkPath.item("1", null, "backdrop"))
        assertEquals("/api/v2/collections/1/artwork/poster", ArtworkPath.collection("1", null))
        assertEquals("/api/v2/people/1/artwork", ArtworkPath.person("1", null))
    }

    @Test
    fun aVideosChannelAvatarIsAtTheAddressTheVideoCarriesElseTheChannelsIdAddress() {
        fun video(extra: String) =
            Serializer.kotlinxSerializationJson.decodeFromString(
                YouTubeVideo.serializer(),
                """{"id":"5","libraryId":"2","channelId":"30","channelTitle":"Workbench",""" +
                    """"title":"V","watched":false,"positionMs":0,"addedAt":"${Json.T}"$extra}""",
            )
        assertEquals(
            "/api/v2/artwork/youtube/c.jpg",
            video(""","channelPosterUrl":"/api/v2/artwork/youtube/c.jpg"""").channelArt,
        )
        assertEquals("/api/v2/items/30/artwork/poster", video("").channelArt)
    }

    @Test
    fun anImageTheServerAllowsKeepingIsReadFromDiskTheSecondTimeAskedForAsNamed() {
        serve("private, max-age=31536000, immutable")
        val path = ArtworkPath.item("1", item("/api/v2/artwork/w500/a.jpg").posterUrl)
        val before = api()
        val first = before.bytes(path)
        // A new Api, as after a restart, shares only the cache directory.
        before.http.cache!!.close()
        val again = api().bytes(path)
        assertEquals("jpg", String(first.body!!))
        assertEquals("jpg", String(again.body!!))
        assertEquals(listOf("/api/v2/artwork/w500/a.jpg"), asked)
    }

    @Test
    fun aChangedAddressIsAskedForAndAnUnchangedOneIsNot() {
        serve("private, max-age=31536000, immutable")
        val api = api()
        val before = ArtworkPath.item("1", item("/api/v2/artwork/w500/a.jpg").posterUrl)
        val same = ArtworkPath.item("1", item("/api/v2/artwork/w500/a.jpg").posterUrl)
        val changed = ArtworkPath.item("1", item("/api/v2/artwork/w500/b.jpg").posterUrl)
        assertEquals(before, same)
        assertNotEquals(before, changed)
        api.bytes(before)
        api.bytes(same)
        api.bytes(changed)
        assertEquals(listOf("/api/v2/artwork/w500/a.jpg", "/api/v2/artwork/w500/b.jpg"), asked)
    }

    @Test
    fun anImageTheServerWantsRevalidatedIsAskedForAgainWithNoGeneration() {
        serve("private, max-age=0")
        val api = api()
        api.bytes(ArtworkPath.item("1", null))
        api.bytes(ArtworkPath.item("1", null))
        assertEquals(List(2) { "/api/v2/items/1/artwork/poster" }, asked)
    }
}
