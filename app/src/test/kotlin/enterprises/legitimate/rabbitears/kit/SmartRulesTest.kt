package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.Collection
import enterprises.legitimate.rabbitears.api.models.Playlist
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.SmartCondition
import enterprises.legitimate.rabbitears.api.models.SmartField
import enterprises.legitimate.rabbitears.api.models.SmartGroup
import enterprises.legitimate.rabbitears.api.models.SmartMatch
import enterprises.legitimate.rabbitears.api.models.SmartOperator
import enterprises.legitimate.rabbitears.api.models.SmartRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRulesTest {
    private val server = FakeServer()
    private val playlists = PlaylistsModel(server.api(), "1")
    private val collections = CollectionsModel(server.api())

    private fun playlist(json: String) =
        Serializer.kotlinxSerializationJson.decodeFromString(Playlist.serializer(), json)

    private fun collection(json: String) =
        Serializer.kotlinxSerializationJson.decodeFromString(Collection.serializer(), json)

    /** "Rainy" titles from before 1990, or any unwatched one. */
    private fun rainy(kind: PlaylistKind? = PlaylistKind.movies) =
        SmartDraft(kind).apply {
            name = " Rainy "
            match = SmartMatch.any
            setValue(0, 0, " Rain ")
            setOperator(0, 0, SmartOperator.contains)
            addCondition(0)
            setField(0, 1, SmartField.year)
            setOperator(0, 1, SmartOperator.lt)
            setValue(0, 1, "1990")
            setMatch(0, SmartMatch.all)
        }

    @Test
    fun eachKindOffersItsOwnFieldsAndACollectionNoPersonalOnes() {
        val movies = SmartCatalog.fields(PlaylistKind.movies)
        assertTrue(SmartField.director in movies && SmartField.watched in movies)
        assertFalse(SmartField.show in movies || SmartField.artist in movies)
        val tv = SmartCatalog.fields(PlaylistKind.tv)
        assertTrue(SmartField.show in tv && SmartField.season in tv && SmartField.episode in tv)
        assertFalse(SmartField.director in tv)
        val music = SmartCatalog.fields(PlaylistKind.music)
        assertTrue(SmartField.album in music && SmartField.lossless in music)
        assertFalse(SmartField.watched in music || SmartField.contentRating in music)
        val collection = SmartCatalog.fields(null)
        assertEquals(movies - SmartCatalog.PERSONAL, collection)
        assertFalse(SmartDraft(null).fields.any { it in SmartCatalog.PERSONAL })
    }

    @Test
    fun eachTypeOffersItsOperatorsWithTheirLabels() {
        assertEquals(
            listOf("is", "is not", "contains", "does not contain", "starts with", "ends with"),
            SmartCatalog.operators(SmartField.title).map {
                SmartCatalog.label(it, SmartField.title)
            },
        )
        assertEquals(
            listOf("=", "≠", "less than", "at most", "more than", "at least"),
            SmartCatalog.operators(SmartField.year).map { SmartCatalog.label(it, SmartField.year) },
        )
        assertEquals(
            listOf("Yes", "No"),
            SmartCatalog.operators(SmartField.hdr).map { SmartCatalog.label(it, SmartField.hdr) },
        )
        assertEquals("min", SmartCatalog.unit(SmartField.runtime))
        assertEquals("s", SmartCatalog.unit(SmartField.duration))
        assertNull(SmartCatalog.unit(SmartField.year))
    }

    @Test
    fun changingAFieldResetsTheOperatorAndClearsAValueOfTheWrongType() {
        val draft = SmartDraft(PlaylistKind.movies)
        draft.setOperator(0, 0, SmartOperator.endsWith)
        draft.setValue(0, 0, "Harbor")
        draft.setField(0, 0, SmartField.cast)
        assertEquals(SmartOperator.`is`, draft.groups[0].conditions[0].operator)
        assertEquals("Harbor", draft.groups[0].conditions[0].value)
        draft.setField(0, 0, SmartField.runtime)
        assertEquals(SmartOperator.`is`, draft.groups[0].conditions[0].operator)
        assertEquals("", draft.groups[0].conditions[0].value)
        draft.setValue(0, 0, "90")
        draft.setOperator(0, 0, SmartOperator.ge)
        draft.setField(0, 0, SmartField.year)
        assertEquals(SmartOperator.`is`, draft.groups[0].conditions[0].operator)
        assertEquals("90", draft.groups[0].conditions[0].value)
        draft.setField(0, 0, SmartField.uhd)
        assertEquals(SmartOperator.isTrue, draft.groups[0].conditions[0].operator)
        assertEquals("", draft.groups[0].conditions[0].value)
    }

    @Test
    fun anotherKindReplacesTheFieldsItLacks() {
        val draft = SmartDraft(PlaylistKind.movies)
        draft.setField(0, 0, SmartField.director)
        draft.setValue(0, 0, "Varda")
        draft.addCondition(0)
        draft.setField(0, 1, SmartField.year)
        draft.choose(PlaylistKind.music)
        assertEquals(SmartField.title, draft.groups[0].conditions[0].field)
        assertEquals("Varda", draft.groups[0].conditions[0].value)
        assertEquals(SmartField.year, draft.groups[0].conditions[1].field)
    }

    @Test
    fun conditionsAndGroupsComeAndGoAndTheLastStays() {
        val draft = SmartDraft(PlaylistKind.tv)
        draft.addGroup()
        draft.addCondition(1)
        assertEquals(listOf(1, 2), draft.groups.map { it.conditions.size })
        draft.remove(1, 0)
        draft.remove(1, 0)
        assertEquals(1, draft.groups.size)
        assertFalse(draft.removable)
        draft.remove(0, 0)
        assertEquals(1, draft.groups.single().conditions.size)
    }

    @Test
    fun theFootnoteShowsOnlyWithAPersonalField() {
        val draft = SmartDraft(PlaylistKind.music)
        assertFalse(draft.personal)
        draft.setField(0, 0, SmartField.playCount)
        assertTrue(draft.personal)
    }

    @Test
    fun blankTextAndAFractionAreRefusedOnTheirRows() {
        val draft = rainy()
        draft.setValue(0, 0, "  ")
        draft.setValue(0, 1, "19.5")
        draft.addCondition(0)
        draft.setField(0, 2, SmartField.hdr)
        assertFalse(draft.validate())
        assertEquals(setOf(0 to 0, 0 to 1), draft.errors.keys)
        draft.setValue(0, 0, "Rain")
        draft.setValue(0, 1, "-3")
        draft.name = " "
        assertFalse(draft.validate())
        assertTrue(draft.errors.isEmpty())
        assertTrue(draft.nameError != null)
    }

    @Test
    fun theBodyCarriesTrimmedTextIntegersAndNoValueForBooleans() {
        val draft = rainy()
        draft.addGroup()
        draft.setField(1, 0, SmartField.watched)
        draft.setOperator(1, 0, SmartOperator.isFalse)
        assertTrue(draft.validate())
        assertEquals(
            SmartRules(
                SmartMatch.any,
                listOf(
                    SmartGroup(
                        SmartMatch.all,
                        listOf(
                            SmartCondition(SmartField.title, SmartOperator.contains, text = "Rain"),
                            SmartCondition(SmartField.year, SmartOperator.lt, number = 1990),
                        ),
                    ),
                    SmartGroup(
                        SmartMatch.all,
                        listOf(SmartCondition(SmartField.watched, SmartOperator.isFalse)),
                    ),
                ),
            ),
            draft.rules(),
        )
    }

    @Test
    fun savedRulesReadBackIntoTheDraft() = runBlocking {
        server.on(
            "GET /profiles/1/playlists/5/rules",
            body =
                """{"match":"all","groups":[{"match":"any","conditions":[""" +
                    """{"field":"runtime","operator":"le","number":95},""" +
                    """{"field":"hdr","operator":"isTrue"}]}]}""",
        )
        val draft = playlists.draft("5", PlaylistKind.movies, "Short")!!
        assertEquals("Short", draft.name)
        assertEquals(PlaylistKind.movies, draft.kind)
        assertEquals(SmartMatch.any, draft.groups[0].match)
        assertEquals("95", draft.groups[0].conditions[0].value)
        assertEquals(SmartOperator.isTrue, draft.groups[0].conditions[1].operator)
    }

    @Test
    fun creatingMakesTheListThenPutsItsRules() = runBlocking {
        server.on("POST /profiles/1/playlists", 201, Json.playlist("5", "Rainy"))
        server.on("PUT /profiles/1/playlists/5/rules", body = RULES)
        server.on("GET /profiles/1/playlists/5", body = Json.playlist("5", "Rainy", smart = true))
        val made = playlists.create(rainy())
        assertEquals("5", made?.id)
        assertTrue(made!!.isSmart)
        assertEquals(listOf("5"), playlists.playlists.map { it.id })
        val body = server.called("POST /profiles/1/playlists").single().body
        assertTrue(body, body.startsWith("""{"name":"Rainy","kind":"movies""""))
        assertTrue(
            server.called("PUT /profiles/1/playlists/5/rules").single().body.contains("\"Rain\""),
        )
    }

    @Test
    fun refusedRulesDeleteTheListJustMadeAndLandOnTheirRows() = runBlocking {
        server.on("POST /profiles/1/playlists", 201, Json.playlist("5", "Rainy"))
        server.on(
            "PUT /profiles/1/playlists/5/rules",
            400,
            Json.refusal(
                "groups.0.conditions.1.number" to "Too far back.",
                "match" to "Pick all or any.",
            ),
        )
        server.on("DELETE /profiles/1/playlists/5", 204)
        val draft = rainy()
        assertNull(playlists.create(draft))
        assertEquals(1, server.called("DELETE /profiles/1/playlists/5").size)
        assertTrue(playlists.playlists.isEmpty())
        assertEquals(mapOf((0 to 1) to "Too far back."), draft.errors)
        assertEquals("Pick all or any.", draft.error)
    }

    @Test
    fun aDraftThatFailsItsChecksSendsNothing() = runBlocking {
        val draft = rainy()
        draft.setValue(0, 1, "soon")
        assertNull(playlists.create(draft))
        assertNull(collections.create(SmartDraft(null)))
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun editingPutsTheRulesAndRenames() = runBlocking {
        server.on("GET /profiles/1/playlists", body = Json.page(Json.playlist("5", smart = true)))
        playlists.load()
        server.on("PUT /profiles/1/playlists/5/rules", body = RULES)
        server.on("PUT /profiles/1/playlists/5", body = Json.playlist("5", "Rainy", smart = true))
        server.on("GET /profiles/1/playlists/5", body = Json.playlist("5", "Rainy", smart = true))
        assertTrue(playlists.saveRules("5", rainy()))
        assertTrue(server.called("PUT /profiles/1/playlists/5").single().body.contains("Rainy"))
        assertEquals("Rainy", playlists.playlists.single().name)
    }

    @Test
    fun aStationsUnlistedPlaylistTakesRulesAlone() = runBlocking {
        server.on("PUT /profiles/1/playlists/12/rules", body = RULES)
        assertTrue(playlists.saveRules("12", rainy()))
        assertEquals(listOf("PUT /profiles/1/playlists/12/rules"), server.calls.map { it.route })
    }

    @Test
    fun aSmartPlaylistIsNotEditableAndNoPickerOffersIt() = runBlocking {
        server.on(
            "GET /profiles/1/playlists",
            body = Json.page(Json.playlist("1"), Json.playlist("2", smart = true)),
        )
        playlists.load()
        assertFalse(playlists.playlists[1].isEditable)
        assertTrue(playlists.playlists[1].editsRules)
        assertFalse(playlists.playlists[0].editsRules)
        assertEquals(listOf("1"), playlists.editable(PlaylistKind.movies).map { it.id })
        val shared = playlist(Json.playlist("3", access = "write", smart = true))
        assertFalse(shared.isEditable || shared.editsRules)
    }

    @Test
    fun aKidMakesNoSmartPlaylist() = runBlocking {
        val kid = PlaylistsModel(server.api(), "1", adult = false)
        assertNull(kid.create(rainy()))
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun aSmartCollectionIsManagedButItsMoviesAreNotAndNoPickerOffersIt() = runBlocking {
        server.on(
            "GET /collections",
            body =
                Json.page(
                    Json.collection("1", "Picks"),
                    Json.collection("2", "Rainy", smart = true),
                    Json.collection("3", "Saga", "tmdb"),
                ),
        )
        collections.load()
        assertEquals(listOf("1"), collections.custom.map { it.id })
        val (picks, smart, saga) = collections.collections
        assertTrue(smart.manageable(adult = true) && !smart.moviesEditable(adult = true))
        assertTrue(picks.moviesEditable(adult = true))
        assertFalse(picks.manageable(adult = false) || smart.manageable(adult = false))
        assertFalse(saga.manageable(adult = true))
    }

    @Test
    fun aSmartCollectionIsMadeThenGivenRulesAndRolledBackWhenRefused() = runBlocking {
        server.on("POST /collections", 201, Json.collection("4", "Rainy"))
        server.on("PUT /collections/4/rules", body = RULES)
        server.on("GET /collections/4", body = Json.collection("4", "Rainy", smart = true))
        assertEquals(true, collections.create(rainy(null))?.smart)
        assertEquals(listOf("4"), collections.collections.map { it.id })

        server.on("POST /collections", 201, Json.collection("6", "Rainy"))
        server.on(
            "PUT /collections/6/rules",
            400,
            Json.refusal("groups.0.conditions.0.field" to "No."),
        )
        server.on("DELETE /collections/6", 204)
        val draft = rainy(null)
        assertNull(collections.create(draft))
        assertEquals(1, server.called("DELETE /collections/6").size)
        assertEquals(listOf("4"), collections.collections.map { it.id })
        assertEquals(mapOf((0 to 0) to "No."), draft.errors)
    }

    @Test
    fun aCollectionsRulesAreReadAndReplaced() = runBlocking {
        server.on("GET /collections/4/rules", body = RULES)
        server.on("PUT /collections/4/rules", body = RULES)
        server.on("GET /collections/4", body = Json.collection("4", "Rainy", smart = true))
        val draft = collections.draft(collection(Json.collection("4", "Rainy", smart = true)))!!
        assertNull(draft.kind)
        assertEquals("Rain", draft.groups[0].conditions[0].value)
        assertTrue(collections.saveRules("4", draft))
        assertEquals(1, server.called("PUT /collections/4/rules").size)
    }

    private companion object {
        const val RULES =
            """{"match":"all","groups":[{"match":"all","conditions":[""" +
                """{"field":"title","operator":"contains","text":"Rain"}]}]}"""
    }
}
