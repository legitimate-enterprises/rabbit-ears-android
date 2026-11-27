package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.AccountKind
import enterprises.legitimate.rabbitears.api.models.PlaylistHouseholdShare
import enterprises.legitimate.rabbitears.api.models.PlaylistHouseholdShareInput
import enterprises.legitimate.rabbitears.api.models.PlaylistShare
import enterprises.legitimate.rabbitears.api.models.PlaylistShareAccess
import enterprises.legitimate.rabbitears.api.models.PlaylistShareChange
import enterprises.legitimate.rabbitears.api.models.PlaylistShareInput
import enterprises.legitimate.rabbitears.api.models.ShareCandidate
import enterprises.legitimate.rabbitears.api.models.ShareHousehold

val PlaylistShareAccess.label: String
    get() = if (this == PlaylistShareAccess.read) "Can view" else "Can edit"

/**
 * A row of the share sheet's Profiles section: [subtitle] names the profile's labeling household
 * only when the owner is in several, and says Kid for a kid. A null access is Not shared; a null
 * [color], from a server before it, draws the initial on a neutral circle.
 */
class ShareProfile(
    val profileId: Long,
    val name: String,
    val kind: AccountKind,
    val subtitle: String,
    val access: PlaylistShareAccess?,
    val color: String?,
) {
    /** A kid only views. */
    val choices: List<PlaylistShareAccess?>
        get() =
            if (kind == AccountKind.kid) listOf(null, PlaylistShareAccess.read)
            else listOf(null, PlaylistShareAccess.read, PlaylistShareAccess.write)
}

/**
 * The owner's view of who has one playlist: the [households] they are in and the [profiles] they
 * can share with, each with its access. Each write reads the shares again.
 */
class PlaylistSharesModel(val api: Api, val profileId: Long, val playlistId: Long) {
    var households by mutableStateOf<List<ShareHousehold>>(emptyList())
        private set

    private var householdShares by mutableStateOf<List<PlaylistHouseholdShare>>(emptyList())

    private var shares by mutableStateOf<List<PlaylistShare>>(emptyList())

    private var candidates by mutableStateOf<List<ShareCandidate>>(emptyList())

    var error by mutableStateOf<String?>(null)
        private set

    fun access(household: ShareHousehold): PlaylistShareAccess? =
        householdShares.firstOrNull { it.householdId == household.id }?.access

    /** Every candidate, then each share with someone no longer among them. */
    val profiles: List<ShareProfile>
        get() {
            val access = shares.associate { it.profileId to it.access }
            val named = households.size > 1
            fun subtitle(householdId: Long?, kind: AccountKind) =
                listOfNotNull(
                        households.firstOrNull { it.id == householdId }?.name?.takeIf { named },
                        "Kid".takeIf { kind == AccountKind.kid },
                    )
                    .joinToString(" · ")
            return candidates.map {
                ShareProfile(
                    it.profileId,
                    it.name,
                    it.kind,
                    subtitle(it.householdId, it.kind),
                    access[it.profileId],
                    it.color,
                )
            } +
                shares
                    .filter { s -> candidates.none { it.profileId == s.profileId } }
                    .map {
                        ShareProfile(
                            it.profileId,
                            it.name,
                            it.kind,
                            subtitle(it.householdId, it.kind),
                            it.access,
                            it.color,
                        )
                    }
        }

    suspend fun load() = write {
        candidates = pages { o ->
            api.call { playlists.listShareCandidates(profileId, limit = PAGE, offset = o ?: 0) }
                .let { it.items to it.nextOffset }
        }
        households = api.call { playlists.listShareHouseholds(profileId) }.items
        reload()
    }

    /** Shares, replaces or, with a null [access], stops sharing, as [household] has it now. */
    suspend fun set(household: ShareHousehold, access: PlaylistShareAccess?) = write {
        val shared = householdShares.any { it.householdId == household.id }
        api.call {
            when {
                access == null ->
                    playlists.deletePlaylistHouseholdShare(profileId, playlistId, household.id)
                !shared ->
                    playlists.createPlaylistHouseholdShare(
                        profileId,
                        playlistId,
                        PlaylistHouseholdShareInput(household.id, access),
                    )
                else ->
                    playlists.replacePlaylistHouseholdShare(
                        profileId,
                        playlistId,
                        household.id,
                        PlaylistShareChange(access),
                    )
            }
        }
        reload()
    }

    /** As for a household; a kid only views, whatever [access] asks. */
    suspend fun set(profile: ShareProfile, access: PlaylistShareAccess?) = write {
        val granted =
            if (access != null && access !in profile.choices) PlaylistShareAccess.read else access
        api.call {
            when {
                granted == null ->
                    playlists.deletePlaylistShare(profileId, playlistId, profile.profileId)
                profile.access == null ->
                    playlists.createPlaylistShare(
                        profileId,
                        playlistId,
                        PlaylistShareInput(profile.profileId, granted),
                    )
                else ->
                    playlists.replacePlaylistShare(
                        profileId,
                        playlistId,
                        profile.profileId,
                        PlaylistShareChange(granted),
                    )
            }
        }
        reload()
    }

    private suspend fun reload() {
        shares = pages { o ->
            api.call {
                    playlists.listPlaylistShares(
                        profileId,
                        playlistId,
                        limit = PAGE,
                        offset = o ?: 0,
                    )
                }
                .let { it.items to it.nextOffset }
        }
        householdShares = pages { o ->
            api.call {
                    playlists.listPlaylistHouseholdShares(
                        profileId,
                        playlistId,
                        limit = PAGE,
                        offset = o ?: 0,
                    )
                }
                .let { it.items to it.nextOffset }
        }
    }

    private suspend fun write(block: suspend () -> Unit): Boolean =
        try {
            block()
            error = null
            true
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
            false
        }

    private companion object {
        const val PAGE = 200
    }
}
