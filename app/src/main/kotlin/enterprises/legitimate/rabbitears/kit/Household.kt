package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.AccountKind
import enterprises.legitimate.rabbitears.api.models.FilterMode
import enterprises.legitimate.rabbitears.api.models.HouseholdInvite
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.MakeAdultInput
import enterprises.legitimate.rabbitears.api.models.PinRequest
import enterprises.legitimate.rabbitears.api.models.ProfileOffer
import enterprises.legitimate.rabbitears.api.models.ProfileOfferInput
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the active profile may do to one member of the household. */
enum class MemberAction {
    EditKid,
    SetPin,
    Remove,
    ResendCode,
    MakeAdult,
    Share;

    companion object {
        /**
         * Any adult edits, removes and sets PINs for kids; each adult sets their own PIN; only the
         * owner manages other adults and makes kids adults. Any adult shares anyone joined whose
         * household this is, themself included. An adult only visiting does none of it.
         */
        fun allowed(member: Account, active: Account?, ownerId: Long?): Set<MemberAction> {
            if (active?.kind != AccountKind.adult || active.present == true) return emptySet()
            val owner = active.id == ownerId
            val share = if (member.pending || member.present == true) emptySet() else setOf(Share)
            if (member.kind == AccountKind.kid)
                return share +
                    if (owner) setOf(EditKid, SetPin, Remove, MakeAdult)
                    else setOf(EditKid, SetPin, Remove)
            if (member.id == active.id) return share + SetPin
            if (!owner) return share
            return share + if (member.pending) setOf(ResendCode, Remove) else setOf(Remove)
        }
    }
}

/** A join code for an adult added to the household, and its link; nothing is ever emailed. */
data class Invite(val code: String, val url: String)

fun AppModel.invite(invite: HouseholdInvite) =
    Invite(invite.joinCode.code, signedIn().endpoint.resolve(invite.joinCode.link))

/** Who a member is, under their name in Manage household. */
fun Account.memberLine(ownerId: Long?): String {
    if (pending) {
        val expires = inviteExpiresAt ?: return "Awaiting sign-in · code expired"
        val day =
            expires
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        return "Awaiting sign-in · code expires $day"
    }
    if (kind == AccountKind.adult) return if (id == ownerId) "Owner" else "Adult"
    if (filterMode != FilterMode.ratings) return "Kid · only titles you choose"
    val ceilings =
        listOfNotNull(
            movieCeiling?.let { "movies up to ${it.value}" },
            tvCeiling?.let { "TV up to ${it.value}" },
        )
    return (listOf("Kid") + ceilings).joinToString(" · ")
}

/** Whether the member is in another household too, so removing them only leaves this one. */
val Account.inOthers: Boolean
    get() = present == true || (households ?: 1) > 1

/** Where else a member is, after [memberLine] beside the shared icon; null when nowhere. */
fun Account.placeLine(): String? =
    when {
        present == true -> "Visiting from another household"
        (households ?: 1) > 1 -> "In $households households"
        else -> null
    }

/** What removing the member does, as its confirmation says. */
val Account.removal: String
    get() =
        when {
            inOthers -> {
                val others = if ((households ?: 2) > 2) "households" else "household"
                "$name stays in their other $others, and nothing of theirs is deleted."
            }
            kind == AccountKind.kid -> "Their account and watch history are deleted."
            pending -> "Their code stops working."
            else -> "Their devices are signed out."
        }

/** An offer under its profile's name: one [incoming] to this household, else one of its own. */
fun ProfileOffer.line(incoming: Boolean): String {
    val kind = if (profileKind == AccountKind.kid) "Kid" else "Adult"
    return if (incoming) "$kind · $fromName offers to share $profileName with your household"
    else "$kind · Offered to $toName, waiting"
}

/** Manage household: every member the server lists, and the changes the active profile may make. */
class HouseholdModel(private val app: AppModel) {
    var members by mutableStateOf<List<Account>>(emptyList())
        private set

    /** Offers of other households' profiles to the active adult, and this household's own. */
    var incoming by mutableStateOf<List<ProfileOffer>>(emptyList())
        private set

    var outgoing by mutableStateOf<List<ProfileOffer>>(emptyList())
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Whether the active adult is only visiting, so may only leave. */
    val visiting: Boolean
        get() = app.serverActiveProfile?.present == true

    fun actions(member: Account): Set<MemberAction> =
        MemberAction.allowed(
            member,
            if (app.canAddKid) app.serverActiveProfile else null,
            app.me?.household?.adultId,
        )

    suspend fun load() {
        try {
            members = call { profiles.listProfiles() }.items.filter { it.revokedAt == null }
            val offers = if (app.canAddKid) call { profiles.listProfileOffers() } else null
            incoming = offers?.incoming.orEmpty()
            outgoing = offers?.outgoing.orEmpty()
            error = null
        } catch (e: AppError) {
            error = e.message
        }
    }

    suspend fun saveKid(kid: Account, form: AddKidForm) = change {
        profiles.replaceProfile(kid.id, form.input)
        when (val pin = form.pinChange) {
            AddKidForm.PinChange.Keep -> Unit
            AddKidForm.PinChange.Clear -> profiles.clearProfilePin(kid.id)
            is AddKidForm.PinChange.Set -> profiles.setProfilePin(kid.id, PinRequest(pin.pin))
        }
    }

    /** Sets the member's PIN, or removes it when null. */
    suspend fun setPin(member: Account, pin: String?) = change {
        if (pin != null) profiles.setProfilePin(member.id, PinRequest(pin))
        else profiles.clearProfilePin(member.id)
    }

    /** The titles on a kid's allow list; one that fails to load is left out. */
    suspend fun allowed(kid: Account): List<Item> = call {
        profiles.listAllowedItems(kid.id).itemIds.mapNotNull {
            runCatching { items.getItem(it) }.getOrNull()
        }
    }

    /** Movies and shows matching [query], the only kinds an allow list holds. */
    suspend fun findTitles(query: String): List<Item> = call {
        items.listItems(q = query, limit = 20).items.filter {
            it.kind == ItemKind.movie || it.kind == ItemKind.show
        }
    }

    suspend fun allow(kid: Account, itemId: Long) = call { profiles.allowItem(kid.id, itemId) }

    suspend fun disallow(kid: Account, itemId: Long) = call {
        profiles.disallowItem(kid.id, itemId)
    }

    /** The offered profile joins this household, and Who's watching. */
    suspend fun accept(offer: ProfileOffer) = change { profiles.acceptProfileOffer(offer.id) }

    /** Declines an offer to this household, or withdraws one of its own. */
    suspend fun dismiss(offer: ProfileOffer) = change { profiles.deleteProfileOffer(offer.id) }

    /**
     * The adults the server's admin connected the active one with, who may be offered a profile.
     * shortcut: the first 50 only; page through them once a household has more.
     */
    suspend fun connectedAdults(): List<Account> = call { profiles.listLinkedAdults().items }

    /** Offers [profile] to [adult], for their household; it shows under Offers until answered. */
    suspend fun offer(profile: Account, adult: Account) = change {
        profiles.createProfileOffer(ProfileOfferInput(profile.id, adult.id))
    }

    /** Takes the visiting adult out of this household, back to Who's watching or signed out. */
    suspend fun leave() {
        val active = app.serverActiveProfile ?: return
        call { profiles.deleteProfile(active.id) }
        app.reenter()
    }

    suspend fun remove(member: Account) = change { profiles.deleteProfile(member.id) }

    /** A fresh 7-day code for a pending adult; their unused ones stop working. */
    suspend fun resendCode(member: Account): Invite =
        app.invite(change { profiles.resendProfileJoinCode(member.id) })

    /** Turns a kid into a pending adult, who joins with the returned code. */
    suspend fun makeAdult(member: Account, email: String): Invite =
        app.invite(
            change(AppError.EmailTaken) {
                profiles.makeProfileAdult(member.id, MakeAdultInput(email))
            },
        )

    /** Runs one request that leaves the members as they are. */
    private suspend fun <T> call(request: Api.() -> T): T =
        try {
            app.signedIn().call(request)
        } catch (e: Exception) {
            throw AppError.from(e, AppError.SignedOut)
        }

    /** Runs one change, then reads the members and Who's watching again. */
    private suspend fun <T> change(conflict: AppError? = null, request: Api.() -> T): T {
        val result =
            try {
                app.signedIn().call(request)
            } catch (e: Exception) {
                if (conflict != null && ApiError.from(e)?.code == "conflict") throw conflict
                throw AppError.from(e, AppError.SignedOut)
            }
        load()
        app.reload()
        return result
    }
}
