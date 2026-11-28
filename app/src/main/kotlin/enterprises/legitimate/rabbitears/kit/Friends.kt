package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.FriendRequestInput
import enterprises.legitimate.rabbitears.api.models.IncomingFriendRequest
import enterprises.legitimate.rabbitears.api.models.OutgoingFriendRequest
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The active adult's friends and friend requests, read from the server on each [load], which the
 * Friends screen asks for when it opens, when the app returns and after each action. A kid [adult]
 * false asks for nothing and has nothing. Nothing here tells whether an email belongs to anyone:
 * every send the server takes answers alike, and a sent request never shows a status. shortcut:
 * online only, an action without the server fails; queue the replayable ones once this client holds
 * the replica.
 */
class FriendsModel(val api: Api, val profileId: String, val adult: Boolean) {
    var friends by mutableStateOf<List<Account>>(emptyList())
        private set

    var received by mutableStateOf<List<IncomingFriendRequest>>(emptyList())
        private set

    var sent by mutableStateOf<List<OutgoingFriendRequest>>(emptyList())
        private set

    /** Each friend's household name, as share lists label them. */
    private var households by mutableStateOf<Map<String, String>>(emptyMap())

    var error by mutableStateOf<String?>(null)
        private set

    /** The count beside Friends in Preferences: requests received. */
    val badge: Int
        get() = received.size

    fun household(friendId: String): String? = households[friendId]

    suspend fun load() {
        if (!adult) return
        try {
            val requests = api.call { profiles.listFriendRequests() }
            friends = pages { o ->
                api.call { profiles.listFriends(limit = PAGE, offset = o ?: 0) }
                    .let { it.items to it.nextOffset }
            }
            received = requests.incoming
            sent = requests.outgoing
            error = null
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
            return
        }
        // listFriends leaves out the household; the share candidates carry its name.
        runCatching {
            pages { o ->
                api.call { playlists.listShareCandidates(profileId, limit = PAGE, offset = o ?: 0) }
                    .let { it.items to it.nextOffset }
            }
        }
            .onSuccess { c -> households = c.associate { it.profileId to it.householdName } }
    }

    /**
     * Asks whoever holds [email] to be friends. Null once the server took it, whoever holds the
     * email, after which [SENT] is the only thing to say; else the line under the field.
     */
    suspend fun send(email: String): String? {
        try {
            api.call { profiles.sendFriendRequest(FriendRequestInput(email.trim())) }
        } catch (e: Exception) {
            return when (ApiError.from(e)?.code) {
                "bad_request" -> "Enter a valid email address."
                "too_many_requests" -> "You've sent a lot of requests today. Try again later."
                else -> AppError.from(e, AppError.SignedOut).message
            }
        }
        load()
        return null
    }

    /**
     * Makes the sender a friend. A request gone meanwhile leaves quietly; one whose sender can no
     * longer be a friend leaves with the notice returned.
     */
    suspend fun accept(request: IncomingFriendRequest): String? {
        try {
            api.call { profiles.acceptFriendRequest(request.id) }
        } catch (e: Exception) {
            when (ApiError.from(e)?.status) {
                NOT_FOUND -> received -= request
                CONFLICT -> {
                    received -= request
                    return "This person can no longer be added."
                }
                else -> error = AppError.from(e, AppError.SignedOut).message
            }
            return null
        }
        load()
        return null
    }

    /**
     * Declines with no confirmation: the row goes at once, and returns if the server never heard.
     */
    suspend fun decline(request: IncomingFriendRequest) {
        received -= request
        if (!gone { profiles.deleteFriendRequest(request.id) })
            received = (received + request).sortedByDescending { it.createdAt }
    }

    suspend fun withdraw(request: OutgoingFriendRequest) {
        if (gone { profiles.deleteFriendRequest(request.id) }) sent -= request
    }

    /** Ends the friendship; the friend is not told. */
    suspend fun remove(friend: Account) {
        if (gone { profiles.deleteFriend(friend.id) }) friends -= friend
    }

    /**
     * Runs a delete the server answers 204 or, for what it no longer has, 404, then reads
     * everything again; false, with [error] set, when it was refused or never arrived.
     */
    private suspend fun gone(request: Api.() -> Unit): Boolean {
        try {
            // A screen that closes mid-write must not take back what the server did.
            withContext(NonCancellable) { api.call(request) }
        } catch (e: Exception) {
            if (ApiError.from(e)?.status != NOT_FOUND) {
                error = AppError.from(e, AppError.SignedOut).message
                return false
            }
        }
        load()
        return true
    }

    companion object {
        const val PAGE = 200
        const val SENT = "If they're on this server, they'll get your friend request."
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")

        /** Whether [text] is shaped like an email; nothing else about it is checked. */
        fun isEmail(text: String) = EMAIL.matches(text.trim())

        /** A sent request's only line: when it was sent and when it expires. */
        fun sentLine(
            request: OutgoingFriendRequest,
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = Locale.getDefault(),
        ): String {
            val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            fun day(at: java.time.OffsetDateTime) = at.atZoneSameInstant(zone).format(date)
            return "Sent ${day(request.createdAt)} · Expires ${day(request.expiresAt)}"
        }
    }
}
