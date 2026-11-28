package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.AppModel
import enterprises.legitimate.rabbitears.kit.PinEntry
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun Who(app: AppModel) {
    // The profile whose PIN is asked for.
    var asking by remember { mutableStateOf(app.pinFor) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun pick(profile: Account) {
        if (profile.hasPin) asking = profile
        else
            scope.launch {
                error =
                    runCatching { app.choose(profile) }
                        .exceptionOrNull()
                        ?.let { AppError.from(it).message }
            }
    }

    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
    ) {
        Text("Who's watching?", style = MaterialTheme.typography.headlineMedium)
        val across = if (LocalWidth.current == WidthClass.Compact) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
            app.profiles.chunked(across).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    row.forEach { profile ->
                        ProfileTile(profile.name, { pick(profile) }) {
                            Avatar(profile, 96.dp, profile.hasPin)
                        }
                    }
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            error?.let { Text(it, color = Palette.accent) }
            Text(
                "This phone is in ${app.householdName ?: "your"}'s household.",
                color = Palette.dim,
            )
            TextButton({ scope.launch { app.signOut() } }) {
                Text("Not you? Sign in as someone else", color = Palette.accent)
            }
        }
    }

    asking?.let { profile -> PinDialog(profile, { app.choose(profile, it) }) { asking = null } }
}

/** Right after signing in, the household of the adult's several that this phone is in. */
@Composable
fun WhichHousehold(app: AppModel) {
    var chosen by remember { mutableStateOf(app.households.first()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
    ) {
        Text(
            "Which household is this phone in?",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "You are in more than one. Who's watching shows the profiles of the household you pick.",
            color = Palette.secondary,
            textAlign = TextAlign.Center,
        )
        Column(
            Modifier.widthIn(max = 480.dp)
                .fillMaxWidth()
                .background(Palette.raised, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            app.households.forEachIndexed { i, household ->
                ChoiceRow(
                    "${household.name}’s household",
                    if (i == 0) "Yours" else "Visiting",
                    household == chosen,
                    { chosen = household },
                ) {
                    Box(
                        Modifier.size(36.dp).background(Palette.tonal, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_home), null, Modifier.size(18.dp))
                    }
                }
            }
        }
        error?.let { Text(it, color = Palette.accent) }
        Pill(
            "Continue",
            {
                busy = true
                scope.launch {
                    error =
                        runCatching { app.chooseHousehold(chosen) }
                            .exceptionOrNull()
                            ?.let { AppError.from(it).message }
                    busy = false
                }
            },
            Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            primary = true,
            enabled = !busy,
        )
        Text(
            "Asked once, after signing in. Sign out to change it.",
            color = Palette.dim,
            textAlign = TextAlign.Center,
        )
    }
}

/** A household or adult picked from several, after its [leading] avatar or badge. */
@Composable
internal fun ChoiceRow(
    name: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, color = Palette.dim, fontSize = 13.sp) }
        }
        RadioButton(
            selected,
            null,
            colors = RadioButtonDefaults.colors(selectedColor = Palette.accent),
        )
    }
}

@Composable
private fun ProfileTile(name: String, onClick: () -> Unit, avatar: @Composable () -> Unit) {
    Column(
        Modifier.width(96.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        avatar()
        Text(
            name,
            color = Palette.text,
            fontSize = 16.sp,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

@Composable
fun PinDialog(profile: Account, submit: suspend (String) -> Unit, dismiss: () -> Unit) {
    val entry = remember { PinEntry() }
    var error by remember { mutableStateOf<AppError?>(null) }
    val scope = rememberCoroutineScope()

    fun type(c: Char) {
        if (error == AppError.PinLocked || !entry.type(c)) return
        val pin = entry.digits
        scope.launch {
            try {
                submit(pin)
                dismiss()
            } catch (e: Exception) {
                error = AppError.from(e)
                entry.clear()
            }
        }
    }

    Dialog(dismiss) {
        Surface(color = Palette.raised, shape = RoundedCornerShape(28.dp)) {
            Column(
                Modifier.widthIn(max = 344.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Avatar(profile, 56.dp)
                Text(
                    "Enter ${profile.name}'s PIN",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 22.sp,
                )
                Row(
                    Modifier.semantics {
                        contentDescription =
                            "PIN, ${entry.digits.length} of ${PinEntry.LENGTH} digits entered"
                    },
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    repeat(PinEntry.LENGTH) {
                        Box(
                            Modifier.size(14.dp)
                                .background(
                                    if (it < entry.digits.length) Palette.accent
                                    else Palette.dim.copy(alpha = 0.6f),
                                    CircleShape,
                                ),
                        )
                    }
                }
                error?.let { Text(it.message, color = Palette.accent) }
                val keys = listOf("123", "456", "789", " 0<")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            row.forEach { key ->
                                when (key) {
                                    ' ' -> Spacer(Modifier.size(72.dp))
                                    '<' ->
                                        Box(
                                            Modifier.size(72.dp).clickable {
                                                entry.deleteBackward()
                                            },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_backspace),
                                                "Delete",
                                                tint = Palette.text,
                                            )
                                        }
                                    else ->
                                        Box(
                                            Modifier.size(72.dp)
                                                .background(Palette.tonal, CircleShape)
                                                .clickable { type(key) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(key.toString(), fontSize = 26.sp)
                                        }
                                }
                            }
                        }
                    }
                }
                Text(
                    "Forgot PIN? An adult in your household can reset it.",
                    color = Palette.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(dismiss) { Text("Cancel", color = Palette.accent) }
                }
            }
        }
    }
}
