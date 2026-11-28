package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.ArtworkKind
import enterprises.legitimate.rabbitears.api.models.PlaylistEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomImagesTest {
    private val server = FakeServer()
    private val model = CustomImageModel(server.api())
    private val playlist = ImageTarget.Playlist(profileId = "1", playlistId = "3")

    private fun entry(id: String, json: String) =
        """{"id":$id,"playlistId":"3","position":$id,"kind":"movie","itemCount":1,"durationMs":0,""" +
            """"addedAt":"${Json.T}","createdAt":"${Json.T}","modifiedAt":"${Json.T}"$json}"""

    @Test
    fun offersEachTitleOnceWithABackdropOnlyForVideo() {
        val entries =
            listOf(
                    entry("1", ""","itemId":"7","item":${Json.item("7", "movie", "Heat")}"""),
                    entry("2", ""","itemId":"7","item":${Json.item("7", "movie", "Heat")}"""),
                    entry("3", ""","itemId":"8""""),
                    entry("4", ""","collectionId":"2""""),
                )
                .map { Serializer.kotlinxSerializationJson.decodeFromString<PlaylistEntry>(it) }
        val choices = CustomImage.choices(entries)
        assertEquals(
            listOf(
                "7" to ArtworkKind.poster,
                "7" to ArtworkKind.backdrop,
                "8" to ArtworkKind.poster,
            ),
            choices.map { it.itemId to it.kind },
        )
        assertEquals("Heat, backdrop", choices[1].label)
    }

    @Test
    fun aChoiceIsPreviewedAtTheAddressItsEntryCarriesElseItsIdAddress() {
        val heat =
            Json.item("7", "movie", "Heat").removeSuffix("}") +
                ""","posterUrl":"/api/v2/artwork/w500/p.jpg","backdropUrl":"/api/v2/artwork/w1280/b.jpg"}"""
        val entries =
            listOf(
                    entry("1", ""","itemId":"7","item":$heat"""),
                    entry("2", ""","itemId":"8","item":${Json.item("8", "movie")}"""),
                )
                .map { Serializer.kotlinxSerializationJson.decodeFromString<PlaylistEntry>(it) }
        assertEquals(
            listOf(
                "/api/v2/artwork/w500/p.jpg",
                "/api/v2/artwork/w1280/b.jpg",
                "/api/v2/items/8/artwork/poster",
                "/api/v2/items/8/artwork/backdrop",
            ),
            CustomImage.choices(entries).map { it.path },
        )
    }

    @Test
    fun setsAnUploadAPickAndALinkThenRemoves() = runBlocking {
        server.on("PUT /profiles/1/playlists/3/image")
        server.on("PUT /stations/5/image")
        server.on("DELETE /stations/5/image", 204)

        assertTrue(model.set(ImageSource.File(byteArrayOf(1, 2, 3)), playlist))
        assertEquals(3, server.called("PUT /profiles/1/playlists/3/image").single().body.length)

        assertTrue(
            model.set(ImageSource.Artwork("7", ArtworkKind.backdrop), ImageTarget.Station("5")),
        )
        assertEquals(
            """{"itemId":"7","kind":"backdrop"}""",
            server.called("PUT /stations/5/image").last().body,
        )

        assertTrue(
            model.set(ImageSource.Link("  https://example.com/a.png "), ImageTarget.Station("5")),
        )
        assertEquals(
            """{"url":"https://example.com/a.png"}""",
            server.called("PUT /stations/5/image").last().body,
        )

        assertTrue(model.remove(ImageTarget.Station("5")))
        assertEquals(1, server.called("DELETE /stations/5/image").size)
        assertNull(model.error)
    }

    @Test
    fun aRefusalNamesTheFieldsReason() = runBlocking {
        server.on(
            "PUT /profiles/1/playlists/3/image",
            400,
            """{"code":"bad_request","message":"Invalid.","fields":[{"field":"url","message":"That link is not an image."}]}""",
        )
        assertFalse(model.set(ImageSource.Link("https://example.com/"), playlist))
        assertEquals("That link is not an image.", model.error)
        assertFalse(model.set(ImageSource.Link("  "), playlist))
        assertEquals(1, server.called("PUT /profiles/1/playlists/3/image").size)
    }

    @Test
    fun anImageRequestKeepsItsPlaylist() {
        val station =
            Serializer.kotlinxSerializationJson.decodeFromString<
                enterprises.legitimate.rabbitears.api.models.Station,
            >(
                StationJson.station("5", StationJson.onAir("off_schedule")),
            )
        val request = ImageRequest.of(station, profileId = "1")
        assertEquals(ImageTarget.Station("5"), request.target)
        assertEquals(station.playlistId, request.playlistId)
    }
}
