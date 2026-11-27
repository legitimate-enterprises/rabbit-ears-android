package enterprises.legitimate.rabbitears.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.ProfileOffer
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.HouseholdModel
import enterprises.legitimate.rabbitears.kit.Invite
import enterprises.legitimate.rabbitears.kit.MemberAction
import enterprises.legitimate.rabbitears.kit.inOthers
import enterprises.legitimate.rabbitears.kit.line
import enterprises.legitimate.rabbitears.kit.memberLine
import enterprises.legitimate.rabbitears.kit.placeLine
import enterprises.legitimate.rabbitears.kit.removal
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The member a Manage household dialog is about, and what it asks. */
private sealed interface Asking {
    data class Pin(val member: Account) : Asking

    data class ToAdult(val member: Account) : Asking

    data class Removing(val member: Account) : Asking

    data class Sharing(val member: Account) : Asking

    data object Leaving : Asking

    data class Handing(val name: String, val invite: Invite) : Asking
}

/** Every member of the household with what the active adult may change; nothing else shows. */
@Composable
fun ManageHousehold() {
    val app = LocalNav.current.app
    val model = remember { HouseholdModel(app) }
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<Asking?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(model, app.live.household) { model.load() }
    BackHandler(editing != null) { editing = null }
    editing?.let { kid ->
        return AddKid(app, kid, model) { editing = null }
    }

    fun run(action: suspend () -> Unit) {
        scope.launch {
            error =
                try {
                    action()
                    null
                } catch (e: AppError) {
                    e.message
                }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackBar("Back")
        Column {
            Text("Manage household", style = MaterialTheme.typography.headlineMedium)
            app.householdName?.let { Text("$it’s household", color = Palette.secondary) }
        }
        (error ?: model.error)?.let { Text(it, color = Palette.accent) }
        if (model.incoming.isNotEmpty() || model.outgoing.isNotEmpty()) {
            SectionTitle("Offers")
            model.incoming.forEach { offer ->
                OfferRow(offer, true) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 52.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Pill("Decline", { run { model.dismiss(offer) } }, Modifier.weight(1f))
                        Pill(
                            "Accept",
                            { run { model.accept(offer) } },
                            Modifier.weight(1f),
                            primary = true,
                        )
                    }
                }
            }
            model.outgoing.forEach { offer ->
                OfferRow(offer, false) {
                    TextButton({ run { model.dismiss(offer) } }) {
                        Text("Withdraw", color = Palette.accent)
                    }
                }
            }
            SectionTitle("Profiles")
        }
        model.members.forEach { member ->
            MemberRow(member, model.actions(member), app.me?.household?.adultId) {
                when (it) {
                    MemberAction.SetPin -> asking = Asking.Pin(member)
                    MemberAction.MakeAdult -> asking = Asking.ToAdult(member)
                    MemberAction.Remove -> asking = Asking.Removing(member)
                    MemberAction.ResendCode ->
                        run { asking = Asking.Handing(member.name, model.resendCode(member)) }
                    MemberAction.EditKid -> editing = member
                    MemberAction.Share -> asking = Asking.Sharing(member)
                }
            }
        }
        if (model.members.any { (it.households ?: 1) > 1 })
            Text(
                "A shared profile is the same in every household it is in: one watch history, " +
                    "one set of playlists and limits. Adults in each of its households can " +
                    "change a kid's settings.",
                color = Palette.dim,
                style = MaterialTheme.typography.bodySmall,
            )
        if (model.visiting)
            TextButton({ asking = Asking.Leaving }) {
                Text("Leave this household…", color = Palette.accent)
            }
    }

    when (val shown = asking) {
        is Asking.Pin -> PinSheet(shown.member, model) { asking = null }
        is Asking.ToAdult ->
            ChangeToAdult(shown.member, model, { asking = Asking.Handing(shown.member.name, it) }) {
                asking = null
            }
        is Asking.Removing -> {
            val member = shown.member
            AlertDialog(
                { asking = null },
                {
                    TextButton(
                        {
                            asking = null
                            run { model.remove(member) }
                        },
                    ) {
                        Text("Remove", color = Palette.accent)
                    }
                },
                dismissButton = { TextButton({ asking = null }) { Text("Cancel") } },
                title = {
                    Text(
                        if (member.inOthers) "Remove ${member.name} from this household?"
                        else "Remove ${member.name}?",
                    )
                },
                text = { Text(member.removal) },
                containerColor = Palette.raised,
            )
        }
        is Asking.Handing -> InviteDialog(shown.name, shown.invite) { asking = null }
        is Asking.Sharing -> OfferSheet(shown.member, model) { asking = null }
        Asking.Leaving ->
            AlertDialog(
                { asking = null },
                {
                    TextButton(
                        {
                            asking = null
                            run { model.leave() }
                        },
                    ) {
                        Text("Leave", color = Palette.accent)
                    }
                },
                dismissButton = { TextButton({ asking = null }) { Text("Cancel") } },
                title = { Text("Leave ${app.householdName}’s household?") },
                text = { Text("You stay in your own household, and nothing of yours is deleted.") },
                containerColor = Palette.raised,
            )
        null -> Unit
    }
}

/**
 * An offer to or of this household with its profile's initial; an incoming one's [actions] go under
 * it, an outgoing one's beside it.
 */
@Composable
private fun OfferRow(offer: ProfileOffer, incoming: Boolean, actions: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Palette.raised, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(Palette.tonal, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(offer.profileName.take(1).uppercase(), fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(offer.profileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    offer.line(incoming),
                    color = Palette.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!incoming) actions()
        }
        if (incoming) actions()
    }
}

@Composable
private fun MemberRow(
    member: Account,
    actions: Set<MemberAction>,
    ownerId: String?,
    act: (MemberAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(Palette.raised, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.alpha(if (member.pending) 0.5f else 1f)) { Avatar(member, 40.dp) }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(member.name, style = MaterialTheme.typography.titleMedium)
                if (member.hasPin)
                    Icon(
                        painterResource(R.drawable.ic_lock),
                        "PIN set",
                        Modifier.size(14.dp),
                        tint = Palette.accent,
                    )
            }
            val place = member.placeLine()
            Text(
                buildAnnotatedString {
                    append(member.memberLine(ownerId))
                    if (place != null) {
                        append(" · ")
                        appendInlineContent(SHARED, " ")
                        append(place)
                    }
                },
                color = Palette.secondary,
                style = MaterialTheme.typography.bodyMedium,
                inlineContent = sharedIcon,
            )
        }
        if (actions.isNotEmpty())
            Box {
                IconButton({ open = true }) {
                    Icon(painterResource(R.drawable.ic_more), "More for ${member.name}")
                }
                DropdownMenu(open, { open = false }) {
                    val labels =
                        listOf(
                            MemberAction.ResendCode to "Resend code",
                            MemberAction.EditKid to "Edit",
                            MemberAction.SetPin to if (member.hasPin) "Change PIN…" else "Set PIN…",
                            MemberAction.Share to "Share with another household…",
                            MemberAction.MakeAdult to "Change to adult…",
                        )
                    labels.forEach { (action, label) ->
                        if (action in actions)
                            MenuAction(label) {
                                open = false
                                act(action)
                            }
                    }
                    if (MemberAction.Remove in actions) {
                        HorizontalDivider(color = Palette.divider)
                        MenuAction(
                            if (member.inOthers) "Remove from this household…" else "Remove…",
                        ) {
                            open = false
                            act(MemberAction.Remove)
                        }
                    }
                }
            }
    }
}

private const val SHARED = "shared"

/** The two people drawn before where else a member is. */
private val sharedIcon =
    mapOf(
        SHARED to
            InlineTextContent(Placeholder(17.sp, 14.sp, PlaceholderVerticalAlign.TextCenter)) {
                Icon(
                    painterResource(R.drawable.ic_people),
                    null,
                    Modifier.padding(end = 3.dp),
                    tint = Palette.secondary,
                )
            },
    )

/** Offers a member to an adult the server's admin connected the active one with. */
@Composable
private fun OfferSheet(member: Account, model: HouseholdModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var adults by remember { mutableStateOf<List<Account>>(emptyList()) }
    var to by remember { mutableStateOf<Account?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(member) {
        try {
            adults = model.connectedAdults()
        } catch (e: AppError) {
            error = e.message
        }
    }
    val name = member.name
    AlertDialog(
        close,
        {
            TextButton(
                {
                    val adult = to ?: return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            model.offer(member, adult)
                            close()
                        } catch (e: AppError) {
                            error = e.message
                        }
                        busy = false
                    }
                },
                enabled = to != null && !busy,
            ) {
                Text("Send offer")
            }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
        title = { Text("Share $name") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Offer $name to an adult in another household. Once they accept, $name " +
                        "appears in Who's watching there with the same watch history, playlists " +
                        "and limits, and the adults there can change $name's settings as you can.",
                    color = Palette.secondary,
                )
                Text("Offer to", style = MaterialTheme.typography.titleSmall)
                adults.forEach { adult ->
                    ChoiceRow(adult.name, null, adult == to, { to = adult }) {
                        Avatar(adult, 36.dp)
                    }
                }
                Text(
                    "Only adults the server's admin has connected you with appear here.",
                    color = Palette.dim,
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = Palette.accent) }
            }
        },
        containerColor = Palette.raised,
    )
}

/** Sets a member's four-digit PIN, or removes the one they have. */
@Composable
private fun PinSheet(member: Account, model: HouseholdModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = pin.length == 4 && pin.all { it in '0'..'9' } && pin == confirmation
    val mismatch = confirmation.isNotEmpty() && confirmation != pin

    fun save(pin: String?) {
        scope.launch {
            try {
                model.setPin(member, pin)
                close()
            } catch (e: AppError) {
                error = e.message
            }
        }
    }

    AlertDialog(
        close,
        { TextButton({ save(pin) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(close) { Text("Cancel") } },
        title = {
            Text(
                if (member.hasPin) "Change ${member.name}’s PIN"
                else "Set a PIN for ${member.name}",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Four digits, asked for when switching to ${member.name} on any device.",
                    color = Palette.secondary,
                )
                val digits = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                OutlinedTextField(
                    pin,
                    { pin = it.take(4) },
                    Modifier.fillMaxWidth(),
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = digits,
                )
                OutlinedTextField(
                    confirmation,
                    { confirmation = it.take(4) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = digits,
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text("PINs don't match.") }) else null,
                )
                error?.let { Text(it, color = Palette.accent) }
                if (member.hasPin)
                    TextButton({ save(null) }) { Text("Remove PIN", color = Palette.accent) }
            }
        },
        containerColor = Palette.raised,
    )
}

/** Makes a kid a pending adult, then [handOver] shows the code they join with. */
@Composable
private fun ChangeToAdult(
    kid: Account,
    model: HouseholdModel,
    handOver: (Invite) -> Unit,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        close,
        {
            TextButton(
                {
                    busy = true
                    scope.launch {
                        try {
                            handOver(model.makeAdult(kid, email.trim()))
                        } catch (e: AppError) {
                            error = e.message
                        }
                        busy = false
                    }
                },
                enabled = !busy && '@' in email,
            ) {
                Text("Change")
            }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
        title = { Text("Change to adult") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${kid.name} keeps their watch history and joins with a code, like any added " +
                        "adult. Their PIN and ratings are removed.",
                    color = Palette.secondary,
                )
                EmailField(email) { email = it }
                error?.let { Text(it, color = Palette.accent) }
            }
        },
        containerColor = Palette.raised,
    )
}

@Composable
internal fun EmailField(email: String, onChange: (String) -> Unit) =
    OutlinedTextField(
        email,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

/** The code and link an added adult joins with, to hand over in person or share. */
@Composable
internal fun InviteDialog(name: String, invite: Invite, close: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        close,
        { TextButton(close) { Text("Done") } },
        title = { Text("Invite $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Give them this link or code. They pick a password when they open it.",
                    color = Palette.secondary,
                )
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            invite.code,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            invite.url,
                            color = Palette.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                }
                Pill(
                    "Share link",
                    {
                        val send =
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, invite.url)
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    Modifier.fillMaxWidth(),
                    primary = true,
                )
            }
        },
        containerColor = Palette.raised,
    )
}

/** The titles a kid on an allow list may see; each change applies at once. */
@Composable
internal fun AllowListEditor(kid: Account, model: HouseholdModel) {
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf<List<Item>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Item>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun run(action: suspend () -> Unit) {
        try {
            action()
            error = null
        } catch (e: AppError) {
            // A newer query cancels the search it replaces.
            if (currentCoroutineContext().isActive) error = e.message
        }
    }

    LaunchedEffect(kid) { run { allowed = model.allowed(kid) } }
    LaunchedEffect(query) {
        val text = query.trim()
        run { found = if (text.isEmpty()) emptyList() else model.findTitles(text) }
    }

    @Composable
    fun Title(item: Item, action: String, act: suspend () -> Unit) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(Catalog.title(item), Modifier.weight(1f))
            Pill(action, { scope.launch { run(act) } })
        }
    }

    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Allowed titles", style = MaterialTheme.typography.titleSmall)
        if (allowed.isEmpty()) Text("No titles yet.", color = Palette.secondary)
        allowed.forEach { item ->
            Title(item, "Remove") {
                model.disallow(kid, item.id)
                allowed = allowed.filter { it.id != item.id }
            }
        }
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth(),
            label = { Text("Add a title") },
            placeholder = { Text("Find a title") },
            singleLine = true,
        )
        found.forEach { item ->
            Title(item, "Add") {
                model.allow(kid, item.id)
                if (allowed.none { it.id == item.id }) allowed = allowed + item
                query = ""
            }
        }
        error?.let { Text(it, color = Palette.accent) }
    }
}
