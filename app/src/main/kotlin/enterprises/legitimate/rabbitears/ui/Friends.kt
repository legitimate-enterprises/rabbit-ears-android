package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.IncomingFriendRequest
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.FriendsModel
import enterprises.legitimate.rabbitears.kit.ProfileColors
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** Reads friends again on open and on each return to the app, as no event tells of a change. */
@Composable
private fun FriendsLoad(model: FriendsModel) {
    var starts by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { starts++ }
    LaunchedEffect(model, starts) { model.load() }
}

/** Preferences' Friends group, for an adult only: the friend count and requests received. */
@Composable
fun FriendsEntry() {
    val nav = LocalNav.current
    val model = nav.friends
    FriendsLoad(model)
    Text("Friends", style = MaterialTheme.typography.titleLarge)
    Row(
        Modifier.fillMaxWidth().clickable { nav.open(Route.Friends) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Friends", style = MaterialTheme.typography.titleMedium)
            Text(Format.plural(model.friends.size, "friend"), color = Palette.secondary)
        }
        if (model.badge > 0)
            Text(
                "${model.badge}",
                Modifier.background(Palette.accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics {
                        contentDescription = Format.plural(model.badge, "friend request")
                    },
                color = Palette.onAccent,
                fontWeight = FontWeight.Bold,
            )
    }
}

/**
 * Requests received, the friends, and the requests sent, collapsed. A sent row never says what
 * became of it; it only goes once withdrawn, accepted or expired.
 */
@Composable
fun Friends() {
    val nav = LocalNav.current
    val model = nav.friends
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Account?>(null) }
    var showSent by remember { mutableStateOf(false) }
    FriendsLoad(model)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { BackBar("Back") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Friends",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Pill("Add Friend", { adding = true }, primary = true)
            }
        }
        model.error?.let { item { Text(it, color = Palette.accent) } }
        if (model.received.isNotEmpty()) {
            item { SectionTitle("Requests") }
            items(model.received, key = { it.id }) { request ->
                RequestRow(request, !nav.app.offline) { accept ->
                    scope.launch {
                        if (accept) model.accept(request)?.let { nav.app.live.notice = it }
                        else model.decline(request)
                    }
                }
            }
        }
        item { SectionTitle("Friends") }
        if (model.friends.isEmpty())
            item {
                Text(
                    "No friends yet. Add someone by their email to share playlists and watch " +
                        "together.",
                    color = Palette.dim,
                )
            }
        items(model.friends, key = { it.id }) { friend ->
            FriendRow(friend, model.household(friend.id)) { removing = friend }
        }
        if (model.sent.isNotEmpty()) {
            item {
                Text(
                    "Sent (${model.sent.size})",
                    Modifier.fillMaxWidth().clickable { showSent = !showSent },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (showSent)
                items(model.sent, key = { it.id }) { request ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(request.email, style = MaterialTheme.typography.titleMedium)
                            Text(FriendsModel.sentLine(request), color = Palette.secondary)
                        }
                        TextButton({ scope.launch { model.withdraw(request) } }) {
                            Text("Withdraw", color = Palette.accent)
                        }
                    }
                }
        }
    }
    if (adding) AddFriend(model) { adding = false }
    removing?.let { friend ->
        AlertDialog(
            { removing = null },
            {
                TextButton(
                    {
                        removing = null
                        scope.launch { model.remove(friend) }
                    },
                ) {
                    Text("Remove", color = Palette.accent)
                }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
            title = { Text("Remove ${friend.name}?") },
            text = {
                Text(
                    "They won't be told. Playlists you've shared stay shared until you stop " +
                        "sharing them.",
                )
            },
            containerColor = Palette.raised,
        )
    }
}

/** A request received, by the sender's name and color alone; [answer] is true for Accept. */
@Composable
private fun RequestRow(
    request: IncomingFriendRequest,
    canAccept: Boolean,
    answer: (Boolean) -> Unit,
) {
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
                Modifier.size(40.dp)
                    .background(Color(ProfileColors.argb(request.fromColor)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    request.fromName.take(1).uppercase(),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column {
                Text(request.fromName, style = MaterialTheme.typography.titleMedium)
                Text("Wants to be friends", color = Palette.secondary)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("Decline", { answer(false) }, Modifier.weight(1f))
            Pill(
                "Accept",
                { answer(true) },
                Modifier.weight(1f),
                primary = true,
                enabled = canAccept,
            )
        }
    }
}

@Composable
private fun FriendRow(friend: Account, household: String?, remove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(Palette.raised, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(friend, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(friend.name, style = MaterialTheme.typography.titleMedium)
            household?.let { Text(it, color = Palette.secondary) }
        }
        Box {
            IconButton({ open = true }) {
                Icon(painterResource(R.drawable.ic_more), "More for ${friend.name}")
            }
            DropdownMenu(open, { open = false }) {
                MenuAction("Remove Friend") {
                    open = false
                    remove()
                }
            }
        }
    }
}

/** Asks for an email and sends a request; whoever holds the email, it closes with one notice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFriend(model: FriendsModel, close: () -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(close, containerColor = Palette.raised) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add Friend", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                email,
                {
                    email = it
                    error = null
                },
                Modifier.fillMaxWidth(),
                label = { Text("Email") },
                placeholder = { Text("name@example.com") },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email,
                    ),
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
            Text(
                "Friends can share playlists and watch together with you. They don't join your " +
                    "household.",
                color = Palette.secondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Cancel", close, Modifier.weight(1f))
                Pill(
                    "Send Request",
                    {
                        busy = true
                        scope.launch {
                            error = model.send(email)
                            busy = false
                            if (error == null) {
                                close()
                                nav.app.live.notice = FriendsModel.SENT
                            }
                        }
                    },
                    Modifier.weight(1f),
                    primary = true,
                    enabled = FriendsModel.isEmail(email) && !busy,
                )
            }
        }
    }
}
