package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.LibraryKind
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySectionsTest {
    private val server = FakeServer()
    private val store = MemoryCredentialStore()

    private fun library(id: String, kind: String) =
        """{"id":$id,"name":"Library $id","kind":"$kind","scanHour":3,"watchFiles":false,""" +
            """"folders":[],"stats":{"movies":0,"shows":0,"seasons":0,"episodes":0,"files":0,""" +
            """"bytes":0,"unmatched":0},"createdAt":"${Json.T}","modifiedAt":"${Json.T}"}"""

    private fun answer(vararg kinds: String) =
        server.on(
            "GET /libraries",
            body = Json.page(*kinds.mapIndexed { i, k -> library("${i + 1}", k) }.toTypedArray()),
        )

    private suspend fun signedIn(
        app: AppModel = AppModel(store, "Pixel 9", server.http),
        at: String = "http://192.168.1.20:8477",
    ): AppModel {
        store.server = at
        store.token = "t1"
        server.on("GET /me", body = Json.me())
        app.restore()
        return app
    }

    @Test
    fun aKindWithNoLibraryHidesItsSection() {
        assertEquals(
            setOf(LibrarySection.movies, LibrarySection.tv, LibrarySection.youtube),
            LibrarySection.visible(listOf(LibraryKind.movies, LibraryKind.tv, LibraryKind.youtube)),
        )
        assertEquals(
            setOf(LibrarySection.music),
            LibrarySection.visible(listOf(LibraryKind.music, LibraryKind.music)),
        )
        assertEquals(emptySet<LibrarySection>(), LibrarySection.visible(emptyList()))
    }

    @Test
    fun nothingKnownYetShowsEverySection() {
        assertEquals(LibrarySection.entries.toSet(), LibrarySection.visible(null))
    }

    @Test
    fun aDestinationThatDisappearsFallsBackToHome() {
        val visible = setOf(LibrarySection.movies)
        assertEquals(LibrarySection.movies, LibrarySection.stay(LibrarySection.movies, visible))
        assertNull(LibrarySection.stay(LibrarySection.music, visible))
        assertNull(LibrarySection.stay(null, visible))
    }

    @Test
    fun aServerWithoutMusicHidesItAndTheAnswerIsRememberedForTheNextLaunch() = runBlocking {
        val app = signedIn()
        assertEquals(LibrarySection.entries.toSet(), app.librarySections)
        answer("movies", "tv", "youtube")
        app.loadLibrarySections()
        val expected = setOf(LibrarySection.movies, LibrarySection.tv, LibrarySection.youtube)
        assertEquals(expected, app.librarySections)

        val launched = signedIn(AppModel(store, "Pixel 9", server.http))
        assertEquals(expected, launched.librarySections)
    }

    @Test
    fun aFailedAnswerKeepsTheLastKnownSet() = runBlocking {
        val app = signedIn()
        answer("music")
        app.loadLibrarySections()
        server.on("GET /libraries", 503, "")
        app.loadLibrarySections()
        assertEquals(setOf(LibrarySection.music), app.librarySections)
    }

    @Test
    fun eachServerRemembersItsOwnAnswer() = runBlocking {
        answer("music")
        signedIn().loadLibrarySections()
        val other = signedIn(at = "http://192.168.1.30:8477")
        assertEquals(LibrarySection.entries.toSet(), other.librarySections)
    }

    @Test
    fun aPullAsksTheServerInsteadOfTheKeptAnswerAndReturnsOnceItIsApplied() = runBlocking {
        val cache = ResponseCache(Files.createTempDirectory("responses").toFile())
        server.on("GET /items", body = Json.page(Json.item("1", "movie", "Dune")))
        server.on("GET /profiles/1/watch-state", body = Json.page())
        answer("movies")
        val app = signedIn(AppModel(store, "Pixel 9", server.http, cache = cache))
        val catalog = CatalogModel(app.api!!, profileId = "1")
        catalog.load()
        server.on("GET /items", body = Json.page(Json.item("2", "movie", "Flow")))
        answer("movies", "music")

        app.refresh { catalog.reload() }

        assertNotNull(catalog.catalog.item("2"))
        assertEquals(setOf(LibrarySection.movies, LibrarySection.music), app.librarySections)
    }
}
