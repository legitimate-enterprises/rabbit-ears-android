package enterprises.legitimate.rabbitears.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.AccountKind
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.ProfileColors
import enterprises.legitimate.rabbitears.kit.adminUrl
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun Preferences() {
    val nav = LocalNav.current
    val app = nav.app
    val profile = app.activeProfile ?: return
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var addingKid by remember { mutableStateOf(false) }
    var addingAdult by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    BackHandler(addingKid) { addingKid = false }
    if (addingKid) return AddKid(app) { addingKid = false }
    if (addingAdult) AddAdult(app) { addingAdult = false }
    if (changingPassword) ChangePassword { changingPassword = false }
    val save = { who: Account, color: String?, hide: Boolean? ->
        scope.launch {
            error =
                runCatching { app.setPreferences(who, color, hide) }
                    .exceptionOrNull()
                    ?.let { AppError.from(it).message }
            // What is hidden changes what the catalog and YouTube list.
            if (hide != null && error == null) {
                nav.catalog.hideKids(hide)
                nav.youtube.loadChannels()
            }
        }
        Unit
    }
    // The downloads listed here are brought up to what the server now says.
    val reload: suspend () -> Unit = {
        nav.downloads?.takeIf { !app.offline }?.sync(nav.catalog.api, profile.id)
    }
    Refreshable(reload) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackBar("Back")
            Text("Preferences", style = MaterialTheme.typography.headlineMedium)
            error?.let { Text(it, color = Palette.accent) }
            Text("Color", style = MaterialTheme.typography.titleMedium)
            Swatches(profile.color) { save(profile, it, null) }
            Toggle(
                "Autoplay",
                "Plays what’s up next 10 seconds after a title ends.",
                profile.autoplay != false,
            ) {
                scope.launch {
                    error =
                        runCatching { app.setPreferences(profile, autoplay = it) }
                            .exceptionOrNull()
                            ?.let { e -> AppError.from(e).message }
                }
            }
            if (!app.isKid) {
                Toggle(
                    "Hide kids’ content",
                    "Leaves kids’ movies, shows and channels out of what you browse.",
                    profile.hideKidsContent == true,
                ) {
                    save(profile, null, it)
                }
                val memory = app.profileMemory
                Toggle(
                    "Remember profile",
                    "Opens this phone as the profile last watching, without Who’s watching.",
                    memory.remember,
                ) {
                    app.rememberProfiles(memory.copy(remember = it))
                }
                if (memory.remember)
                    Toggle(
                        "Skip PIN",
                        "Opens a remembered profile that has a PIN without asking for it.",
                        memory.skipPin,
                    ) {
                        app.rememberProfiles(memory.copy(skipPin = it))
                    }
                app.profiles
                    .filter { it.kind == AccountKind.kid }
                    .forEach { kid ->
                        Text("${kid.name}’s color", style = MaterialTheme.typography.titleMedium)
                        Swatches(kid.color) { save(kid, it, null) }
                    }
            }
            DownloadsPreferences()
            if (!app.isKid) {
                Text("Household", style = MaterialTheme.typography.titleLarge)
                if (app.canAddKid) Pill("Add a kid", { addingKid = true })
                if (app.isOwner) Pill("Add an adult", { addingAdult = true })
                Pill("Manage household", { nav.open(Route.Household) })
                Text("Account", style = MaterialTheme.typography.titleLarge)
                Pill("Password", { changingPassword = true })
                app.adminUrl?.let { admin -> Pill("Admin", { uri.openUri(admin) }) }
                DeleteAccount()
            }
        }
    }
}

@Composable
internal fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = Palette.secondary)
        }
        Switch(
            checked,
            onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent),
        )
    }
}

@Composable
private fun Swatches(selected: String?, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileColors.PALETTE.forEach { hex ->
            val chosen = hex.equals(selected, ignoreCase = true)
            Row(
                Modifier.size(44.dp)
                    .background(Color(ProfileColors.argb(hex)), CircleShape)
                    .then(
                        if (chosen) Modifier.border(3.dp, Palette.accent, CircleShape)
                        else Modifier,
                    )
                    .semantics {
                        contentDescription = hex
                        role = Role.RadioButton
                        this.selected = chosen
                    }
                    .clickable { onPick(hex) },
            ) {}
        }
    }
}
