package enterprises.legitimate.rabbitears.kit

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Answers the generated client without a network: routes are "METHOD /path" under /api/v2, query
 * ignored, and every request is recorded with its body and Authorization header.
 */
class FakeServer {
    data class Call(val route: String, val query: String?, val body: String, val auth: String?)

    private val routes = mutableMapOf<String, Pair<Int, String>>()
    val calls = mutableListOf<Call>()

    /** Runs on the caller's thread once a request is recorded, so a test can hold its answer. */
    var before: (route: String) -> Unit = {}

    /**
     * An answer by the whole request, such as its host, ahead of the routes; null falls to them.
     */
    var respond: (Request) -> Pair<Int, String>? = { null }

    fun on(route: String, status: Int = 200, body: String = "") {
        routes[route] = status to body
    }

    fun error(route: String, status: Int, code: String, message: String = code) =
        on(route, status, """{"code":"$code","message":"$message"}""")

    fun called(route: String) = calls.filter { it.route == route }

    val http: OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val path = request.url.encodedPath.removePrefix("/api/v2")
                    val route = "${request.method} $path"
                    val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    synchronized(calls) {
                        calls +=
                            Call(route, request.url.query, body, request.header("Authorization"))
                    }
                    before(route)
                    val (status, text) =
                        respond(request) ?: routes[route] ?: error("no fake answer for $route")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("fake")
                        .body(text.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

    fun api(token: String? = "tok") = Api(ServerEndpoint.parse("media.example.com")!!, token, http)
}

/** JSON builders for the shapes the tests need, with every required field. */
object Json {
    const val T = "2025-01-01T00:00:00Z"

    fun account(
        id: String,
        name: String,
        kind: String = "adult",
        hasPin: Boolean = false,
        pending: Boolean = false,
        filterMode: String = "ratings",
        color: String? = null,
        hide: Boolean? = null,
    ) =
        """{"id":$id,"kind":"$kind","name":"$name","isAdmin":false,"hasPin":$hasPin,"pinLocked":false,""" +
            """"pending":$pending,"filterMode":"$filterMode","createdAt":"$T","modifiedAt":"$T"""" +
            (color?.let { ""","color":"$it"""" } ?: "") +
            (hide?.let { ""","hideKidsContent":$it""" } ?: "") +
            "}"

    fun me(active: Long = 1, vararg profiles: String = arrayOf(account("1", "Helga"))) =
        """{"device":{"id":"9","name":"Pixel","platform":"android","accountId":"1","householdId":"1",""" +
            """"createdAt":"$T","modifiedAt":"$T"},"account":${account("1", "Helga")},"activeProfileId":$active,""" +
            """"profiles":[${profiles.joinToString(",")}],""" +
            """"household":{"id":"1","name":"Helga","adultId":"1","createdAt":"$T","modifiedAt":"$T"}}"""

    fun item(
        id: String,
        kind: String,
        title: String = "Item $id",
        parent: Long? = null,
        season: Int? = null,
        episode: Int? = null,
        added: String = T,
        runtime: Int? = null,
        rating: String? = null,
        year: Int? = null,
        genres: String = "[]",
        kids: Boolean? = null,
        sort: String? = null,
    ): String {
        val extra = buildList {
            sort?.let { add(""""sortTitle":"$it"""") }
            kids?.let { add(""""kidsContent":$it""") }
            parent?.let { add(""""parentId":$it""") }
            season?.let { add(""""seasonNumber":$it""") }
            episode?.let { add(""""episodeNumber":$it""") }
            year?.let { add(""""parsedYear":$it""") }
        }
        val meta = buildList {
            add(""""status":"matched","title":"$title"""")
            runtime?.let { add(""""runtimeMinutes":$it""") }
            rating?.let { add(""""contentRating":"$it"""") }
        }
        return """{"id":$id,"libraryId":"1","kind":"$kind","parsedTitle":"$title","addedAt":"$added",""" +
            """"createdAt":"$T","modifiedAt":"$T","metadata":{${meta.joinToString(",")}},"genres":$genres""" +
            extra.joinToString("") { ",$it" } +
            "}"
    }

    fun watch(item: Long, position: Long = 0, watched: Boolean = false, played: String = T) =
        """{"itemId":$item,"positionMs":$position,"watched":$watched,"playCount":0,""" +
            """"changedAt":"$T","lastPlayedAt":"$played"}"""

    fun collection(
        id: String,
        name: String,
        source: String = "custom",
        movies: Int = 2,
        customOrder: Boolean = false,
        smart: Boolean = false,
    ) =
        """{"id":$id,"source":"$source","name":"$name","movieCount":$movies,"customOrder":$customOrder,""" +
            """"smart":$smart,"createdAt":"$T","modifiedAt":"$T"}"""

    fun playlist(
        id: String,
        name: String = "Road trip",
        kind: String = "movies",
        access: String = "owner",
        smart: Boolean = false,
    ) =
        """{"id":$id,"profileId":"1","name":"$name","kind":"$kind","itemCount":0,"access":"$access",""" +
            """"smart":$smart,"durationMs":0,"createdAt":"$T","modifiedAt":"$T"}"""

    /** A 400 naming each of [fields], path to message. */
    fun refusal(vararg fields: Pair<String, String>) =
        """{"code":"bad_request","message":"Invalid.","fields":[""" +
            fields.joinToString(",") { (f, m) -> """{"field":"$f","message":"$m"}""" } +
            "]}"

    fun page(vararg items: String) =
        """{"items":[${items.joinToString(",")}],"total":${items.size}}"""
}
