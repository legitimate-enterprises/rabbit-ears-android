package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.AcceptRequest
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.AccountKind
import enterprises.legitimate.rabbitears.api.models.CeilingSuggestion
import enterprises.legitimate.rabbitears.api.models.DeviceHouseholdInput
import enterprises.legitimate.rabbitears.api.models.Event
import enterprises.legitimate.rabbitears.api.models.EventKind
import enterprises.legitimate.rabbitears.api.models.FilterMode
import enterprises.legitimate.rabbitears.api.models.HouseholdChoice
import enterprises.legitimate.rabbitears.api.models.JoinPreview
import enterprises.legitimate.rabbitears.api.models.Me
import enterprises.legitimate.rabbitears.api.models.PinRequest
import enterprises.legitimate.rabbitears.api.models.Platform
import enterprises.legitimate.rabbitears.api.models.ProfilePreferences
import enterprises.legitimate.rabbitears.api.models.SignInRequest
import enterprises.legitimate.rabbitears.api.models.SignInResult
import enterprises.legitimate.rabbitears.api.models.UnlockRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** The signed-in state of this device: which server, which household, which profile is watching. */
class AppModel(
    private val store: CredentialStore,
    private val deviceName: String,
    private val http: OkHttpClient = Api.shared,
    /** This device's downloads, which outlast a server out of reach and go with the sign-in. */
    private val downloads: DownloadStore? = null,
    /** The server's answers kept for the profile active, which go with the sign-in too. */
    private val cache: ResponseCache? = null,
) {
    /** [Phase.Unreachable] keeps the stored session: only the server's unauthorized signs out. */
    enum class Phase {
        Starting,
        Unreachable,
        SignedOut,
        ChoosingHousehold,
        ChoosingProfile,
        Browsing,
    }

    /** What the server says has changed, for what is on screen to load again. */
    val live = LiveFeed(cache)

    var phase by mutableStateOf(Phase.Starting)
        private set

    /** Browsing what is downloaded, the server out of reach; [reconnect] tries it again. */
    var offline by mutableStateOf(false)
        private set

    var me by mutableStateOf<Me?>(null)
        private set

    var activeProfile by mutableStateOf<Account?>(null)
        private set

    var api by mutableStateOf<Api?>(null)
        private set

    /**
     * The sections the server has a library for: every one until it has answered, and from the last
     * answer for this server on a later launch, so destinations do not flash in and out.
     */
    var librarySections by mutableStateOf(LibrarySection.entries.toSet())
        private set

    /** The signed-in adult's Remember profile and Skip PIN on this device. */
    var profileMemory by mutableStateOf(ProfileMemory())
        private set

    /** The remembered profile whose PIN Who's watching asks for at once. */
    var pinFor by mutableStateOf<Account?>(null)
        private set

    /**
     * The households of an adult just signed in who is in several, their own first, for
     * [chooseHousehold]; empty otherwise.
     */
    var households by mutableStateOf<List<HouseholdChoice>>(emptyList())
        private set

    /** What the sign-in answered, entered once [chooseHousehold] leaves the device where it is. */
    private var arrived: Me? = null

    /** Household members to choose from; invited adults appear once they join. */
    val profiles: List<Account>
        get() = me?.profiles.orEmpty().filter { !it.pending && it.revokedAt == null }

    /** The profile the server answers as, which differs from [activeProfile] on Who's watching. */
    val serverActiveProfile: Account?
        get() = me?.let { me ->
            if (me.account.id == me.activeProfileId) me.account
            else me.profiles.firstOrNull { it.id == me.activeProfileId }
        }

    /**
     * Any adult of the household adds kids while they are the server's active profile, unlocked
     * first when [locked]; an adult only visiting it manages nothing there.
     */
    val canAddKid: Boolean
        get() =
            serverActiveProfile?.let { it.kind == AccountKind.adult && it.present != true } == true

    /** Whether the server's active profile owns the household, so manages its adults. */
    val isOwner: Boolean
        get() = me?.let { it.household?.adultId == it.activeProfileId } == true

    /** Held at Who's watching by the server until a profile is unlocked. */
    val locked: Boolean
        get() = me?.locked == true

    /** Kids whose filter is a manual allow list, which an adult adds titles and channels to. */
    val listKids: List<Account>
        get() = profiles.filter {
            it.kind == AccountKind.kid && it.filterMode == FilterMode.allow_list
        }

    val isKid: Boolean
        get() = activeProfile?.kind == AccountKind.kid

    val householdName: String?
        get() = me?.let { it.household?.name ?: it.account.name }

    val lastServer: String
        get() = store.server.orEmpty()

    /** What the server last called itself, stored so it shows before the network answers. */
    private var serverName by mutableStateOf(store.serverName)

    /**
     * Names the place [typed] reaches, for headings: the server's own name once it is the stored
     * server and has given one, else the product's.
     */
    fun placeName(typed: String): String =
        serverName?.takeIf { ServerEndpoint.parse(typed)?.base == store.server } ?: "Rabbit Ears"

    private fun nameServer(name: String?) {
        serverName = name?.takeIf { it.isNotBlank() }
        store.serverName = serverName
    }

    /** Best effort: a server out of reach keeps the name last stored. */
    private suspend fun refreshName(api: Api) {
        runCatching { api.call { system.getHealth() } }.onSuccess { nameServer(it.serverName) }
    }

    /** Resumes the stored session, or asks to sign in when there is none or it was revoked. */
    suspend fun restore() {
        val server = store.server?.let(ServerEndpoint::parse)
        val token = store.token
        if (server == null || token == null || !server.isSecure) {
            phase = Phase.SignedOut
            return
        }
        phase = Phase.Starting
        val api = Api(server, token, http, ::lockedOut, cache)
        try {
            enter(api, api.call { auth.getMe() }, launching = true)
            refreshName(api)
        } catch (e: Exception) {
            // Only the server's own code says it no longer knows the device; a proxy's 401 does
            // not.
            if (ApiError.from(e)?.code != "unauthorized") {
                if (!browseOffline(api)) phase = Phase.Unreachable
                return
            }
            store.token = null
            downloads?.wipe()
            cache?.clear()
            phase = Phase.SignedOut
        }
    }

    /**
     * With the server out of reach and something downloaded, browses the household as last kept:
     * the remembered profile, unless [asksPin], else Who's watching. False leaves nothing to show.
     */
    private fun browseOffline(api: Api): Boolean {
        val stored = downloads ?: return false
        if (stored.state.titles.values.none { it.state == TitleState.OnDevice }) return false
        val me =
            stored.state.me?.let {
                runCatching { DownloadStore.json.decodeFromString(Me.serializer(), it) }.getOrNull()
            } ?: return false
        this.api = api
        this.me = me
        offline = true
        recallSections()
        activeProfile = null
        pinFor = null
        profileMemory = store.profileMemory(memoryKey(me))
        val last = profiles.firstOrNull { it.id == me.activeProfileId }
        if (profileMemory.remember && last != null && !asksPin(last)) {
            activeProfile = last
            phase = Phase.Browsing
        } else phase = Phase.ChoosingProfile
        // With no server to say so, the profile browsing is the one whose kept answers show.
        cache?.profile = activeProfile?.id
        return true
    }

    /**
     * While browsing offline, asks the server again. Reached, it carries on as the profile browsing
     * when the server still has it active and unlocked, else at Who's watching; the server's
     * unauthorized signs out.
     */
    suspend fun reconnect() {
        val api = api ?: return
        if (!offline) return
        val fresh =
            try {
                api.call { auth.getMe() }
            } catch (e: Exception) {
                if (ApiError.from(e)?.code == "unauthorized") signOut()
                return
            }
        val browsing = activeProfile
        if (browsing != null && fresh.activeProfileId == browsing.id && fresh.locked != true) {
            offline = false
            me = fresh.also(::heard)
            activeProfile = known(browsing.id) ?: browsing
        } else enter(api, fresh)
    }

    suspend fun signIn(server: String, email: String, password: String) {
        val endpoint = secure(server)
        val request = SignInRequest(email.trim(), password, deviceName, Platform.android)
        val result: SignInResult
        try {
            result = Api(endpoint, null, http).call { auth.signIn(request) }
        } catch (e: Exception) {
            throw AppError.from(e)
        }
        adopt(endpoint, result)
    }

    /** Who an invite is for and who sent it, asked before any account exists. */
    suspend fun joinPreview(server: String, code: String): JoinPreview {
        val endpoint = secure(server)
        val canonical = JoinCode.normalize(code) ?: throw AppError.InviteInvalid
        return try {
            Api(endpoint, null, http).call { auth.getJoin(canonical) }
        } catch (e: Exception) {
            throw if (ApiError.from(e)?.status == 404) AppError.InviteInvalid else AppError.from(e)
        }
    }

    /** Creates the invited adult's account with the code, which it uses up, and signs in. */
    suspend fun acceptJoin(server: String, code: String, name: String, password: String) {
        val endpoint = secure(server)
        val canonical = JoinCode.normalize(code) ?: throw AppError.InviteInvalid
        val request = AcceptRequest(name, password, deviceName, Platform.android)
        val result: SignInResult
        try {
            result = Api(endpoint, null, http).call { auth.acceptJoin(canonical, request) }
        } catch (e: Exception) {
            throw if (ApiError.from(e)?.status == 404) AppError.InviteInvalid else AppError.from(e)
        }
        adopt(endpoint, result)
    }

    /**
     * The server typed, refused before anything is sent where a password would cross in the clear.
     */
    private fun secure(server: String): ServerEndpoint {
        val endpoint = ServerEndpoint.parse(server) ?: throw AppError.BadServer
        if (!endpoint.isSecure) throw AppError.InsecureServer
        return endpoint
    }

    /**
     * Keeps the session a sign-in or join returned, then asks which household this device is in
     * when the adult is in several, else who's watching.
     */
    private suspend fun adopt(endpoint: ServerEndpoint, result: SignInResult) {
        if (store.server != endpoint.base) nameServer(null)
        store.server = endpoint.base
        store.token = result.token
        // A token the store does not keep is signed out at once: the next launch would find no
        // token and land on sign-in unexplained.
        if (store.token != result.token) {
            runCatching { Api(endpoint, result.token, http).call { auth.signOut() } }
            throw AppError.TokenNotKept
        }
        val api = Api(endpoint, result.token, http, ::lockedOut, cache)
        val choices = result.households.orEmpty()
        if (choices.size > 1) {
            this.api = api
            arrived = result.me
            households = choices
            phase = Phase.ChoosingHousehold
        } else enter(api, result.me)
        refreshName(api)
    }

    /** Puts this device in [household], one of [households], then asks who's watching there. */
    suspend fun chooseHousehold(household: HouseholdChoice) {
        val api = signedIn()
        val me =
            arrived.takeIf { household == households.firstOrNull() }
                ?: try {
                    api.call { profiles.moveDevice(DeviceHouseholdInput(household.id)) }
                    api.call { auth.getMe() }
                } catch (e: Exception) {
                    throw AppError.from(e, AppError.SignedOut)
                }
        arrived = null
        households = emptyList()
        enter(api, me)
    }

    /**
     * Asks who's watching again once the active profile left the device's household; the server
     * signs out a device of their own there.
     */
    suspend fun reenter() {
        val api = signedIn()
        val me =
            try {
                api.call { auth.getMe() }
            } catch (e: Exception) {
                if (ApiError.from(e)?.code == "unauthorized") return signOut()
                throw AppError.from(e, AppError.SignedOut)
            }
        enter(api, me)
    }

    /** Switches this device to a profile; the server checks the PIN. */
    suspend fun choose(profile: Account, pin: String? = null) {
        if (offline) {
            // With no server to check it, a PIN is never taken on trust.
            if (profile.hasPin) throw AppError.PinNeedsServer
            activeProfile = profile
            cache?.profile = profile.id
            phase = Phase.Browsing
            return
        }
        unlock(profile, pin)
        activeProfile = profile
        phase = Phase.Browsing
    }

    /**
     * Makes a profile the server's active one and unlocks this device, staying on Who's watching.
     */
    suspend fun unlock(profile: Account, pin: String? = null) {
        val api = signedIn()
        try {
            api.call { profiles.unlockProfile(profile.id, UnlockRequest(pin)) }
        } catch (e: Exception) {
            if (ApiError.from(e)?.code == "forbidden") throw AppError.WrongPin
            throw AppError.from(e, AppError.SignedOut)
        }
        me = me?.copy(activeProfileId = profile.id, locked = false)?.also(::keep)
    }

    suspend fun switchProfile() {
        if (me == null) return
        pinFor = null
        lock()
        activeProfile = null
        phase = Phase.ChoosingProfile
    }

    /**
     * Holds this device at Who's watching; a server that does not answer stays as it was. The
     * household kept for offline is held regardless, so no relaunch without a server skips a PIN.
     */
    suspend fun lock() {
        me?.let { keep(it.copy(locked = true)) }
        if (offline) {
            cache?.profile = null
            return
        }
        runCatching { signedIn().call { profiles.lockDevice() } }
            .onSuccess { me = me?.copy(locked = true) }
    }

    suspend fun suggestCeilings(form: AddKidForm): CeilingSuggestion? = runCatching {
        signedIn().call { profiles.suggestCeilings(form.birthday) }
    }
        .getOrNull()

    suspend fun addKid(form: AddKidForm) {
        val api = signedIn()
        try {
            val kid = api.call { profiles.createProfile(form.input) }
            if (form.requirePin) api.call { profiles.setProfilePin(kid.id, PinRequest(form.pin)) }
            me = api.call { auth.getMe() }.also(::heard)
        } catch (e: Exception) {
            throw AppError.from(e, AppError.SignedOut)
        }
    }

    /**
     * Saves a profile's color, its Autoplay or, for an adult's own, whether kids' content is
     * hidden, then reads the household again so every avatar shows it.
     */
    suspend fun setPreferences(
        profile: Account,
        color: String? = null,
        hideKidsContent: Boolean? = null,
        autoplay: Boolean? = null,
    ) {
        val api = signedIn()
        try {
            api.call {
                profiles.setProfilePreferences(
                    profile.id,
                    ProfilePreferences(color, hideKidsContent, autoplay),
                )
            }
            me = api.call { auth.getMe() }.also(::heard)
            activeProfile = activeProfile?.let { active -> known(active.id) ?: active }
        } catch (e: Exception) {
            throw AppError.from(e, AppError.SignedOut)
        }
    }

    private fun recallSections() {
        librarySections = LibrarySection.visible(store.server?.let(store::libraryKinds))
    }

    /** A server that cannot answer keeps the sections as they were. */
    suspend fun loadLibrarySections() {
        val api = api?.takeIf { !offline } ?: return
        val kinds =
            runCatching {
                pages { o ->
                    api.call { libraries.listLibraries(CatalogModel.PAGE, o ?: 0) }
                        .let { it.items to it.nextOffset }
                }
            }
                .getOrNull()
                ?.map { it.kind }
                ?.toSet() ?: return
        store.server?.let { store.setLibraryKinds(it, kinds) }
        librarySections = LibrarySection.visible(kinds)
    }

    /** A library change, or a batch that says to read everything, changes the sections. */
    suspend fun librariesChanged(batch: List<Event>) {
        if (batch.isEmpty() || batch.any { it.kind == EventKind.library }) loadLibrarySections()
    }

    /**
     * What a pull to refresh runs: the kept answers are set aside so every read asks the server,
     * then the sections and [reload] are read, and it returns once both are applied.
     */
    suspend fun refresh(reload: suspend () -> Unit) {
        cache?.expire()
        coroutineScope {
            launch { loadLibrarySections() }
            reload()
        }
    }

    /** Reads the household again after a change made elsewhere; a server that fails keeps it. */
    suspend fun reload() {
        runCatching { signedIn().call { auth.getMe() } }.onSuccess { me = it.also(::keep) }
        activeProfile = activeProfile?.let { active -> known(active.id) ?: active }
    }

    private fun known(id: Long): Account? = me?.let { me ->
        me.profiles.firstOrNull { it.id == id } ?: me.account.takeIf { it.id == id }
    }

    /** Signs out here at once; telling the server waits on a server that may not answer. */
    suspend fun signOut() {
        val signedIn = api
        store.token = null
        downloads?.wipe()
        cache?.clear()
        offline = false
        me = null
        activeProfile = null
        api = null
        phase = Phase.SignedOut
        runCatching { signedIn?.call { auth.signOut() } }
    }

    fun rememberProfiles(memory: ProfileMemory) {
        me?.let { store.setProfileMemory(memoryKey(it), memory) }
        profileMemory = memory
    }

    private fun memoryKey(me: Me) = "${store.server}/${me.account.id}"

    fun signedIn(): Api = api ?: throw AppError.SignedOut

    /** Keeps [me] for [browseOffline], so a launch without a server opens as this device stands. */
    private fun keep(me: Me) =
        downloads?.setMe(DownloadStore.json.encodeToString(Me.serializer(), me))

    /** Keeps the server's answer [me], whose household drops the downloads of profiles it lost. */
    private fun heard(me: Me) {
        keep(me)
        downloads?.prune(me)
    }

    private suspend fun enter(api: Api, me: Me, launching: Boolean = false) {
        this.api = api
        this.me = me
        offline = false
        heard(me)
        recallSections()
        activeProfile = null
        pinFor = null
        profileMemory = store.profileMemory(memoryKey(me))
        if (launching && profileMemory.remember && resume()) return
        if (me.locked != true) lock()
        phase = Phase.ChoosingProfile
    }

    /**
     * Continues as the server's active profile, the one last active on this device. False leaves
     * Who's watching to show.
     */
    private suspend fun resume(): Boolean {
        val last = profiles.firstOrNull { it.id == me?.activeProfileId } ?: return false
        if (asksPin(last)) {
            pinFor = last
            return false
        }
        if (locked && runCatching { unlock(last) }.isFailure) return false
        activeProfile = last
        phase = Phase.Browsing
        return true
    }

    /**
     * Whether continuing as [profile] asks its PIN: on a locked device, since the server unlocks a
     * PIN profile only with it, or with Skip PIN off.
     */
    private fun asksPin(profile: Account) = profile.hasPin && (locked || !profileMemory.skipPin)

    /** The server answered device_locked, so another profile must be picked. */
    private fun lockedOut() {
        if (me == null) return
        me = me?.copy(locked = true)?.also(::keep)
        activeProfile = null
        pinFor = null
        phase = Phase.ChoosingProfile
    }
}
