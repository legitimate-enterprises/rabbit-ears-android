package enterprises.legitimate.rabbitears.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.api.models.JoinPreview
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.AppModel
import enterprises.legitimate.rabbitears.kit.JoinCode
import enterprises.legitimate.rabbitears.kit.ServerEndpoint
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** An invited adult looks up their code, then picks a password to create their account. */
@Composable
fun Join(app: AppModel, back: () -> Unit) {
    var server by rememberSaveable {
        mutableStateOf(ServerEndpoint.parse(app.lastServer)?.typed.orEmpty())
    }
    var code by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<JoinPreview?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    // Saved instance state may be written to disk by the system; a password stays in memory.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<AppError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = back)
    val passwordError =
        "Use at least 12 characters.".takeIf { password.isNotEmpty() && password.length < 12 }
    val confirmError =
        "Passwords don't match.".takeIf { confirmation.isNotEmpty() && confirmation != password }
    val canCreate =
        !busy && password.isNotEmpty() && passwordError == null && confirmation == password

    fun attempt(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
                error = null
            } catch (e: AppError) {
                error = e
            }
            busy = false
        }
    }

    AccountPage(app.placeName(server)) {
        val found = preview
        when {
            error == AppError.InviteInvalid -> {
                Text(
                    "This invite is no longer valid.",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text("Ask the person who invited you for a new one.", color = Palette.secondary)
                TextButton(back) { Text("Sign in", color = Palette.accent) }
            }
            found == null -> {
                Text("Join with an invite", style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(
                    server,
                    { server = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Server") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    code,
                    { text ->
                        val link = JoinCode.parseLink(text)
                        if (link != null) {
                            server = link.first.typed
                            code = link.second
                        } else code = text
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("Invite code or link") },
                    placeholder = { Text("XXXX-XXXX") },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                        ),
                )
                error?.let { Text(it.message, color = Palette.accent) }
                Pill(
                    "Continue",
                    {
                        attempt {
                            val looked = app.joinPreview(server, code)
                            name = looked.name
                            preview = looked
                        }
                    },
                    Modifier.fillMaxWidth().height(48.dp),
                    primary = true,
                    enabled = !busy,
                )
                TextButton(back, Modifier.fillMaxWidth()) {
                    Text("Sign in instead", color = Palette.accent)
                }
            }
            else -> {
                Text(
                    "${found.invitedBy ?: "Someone"} invited you to ${app.placeName(server)}",
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Create your account to join.", color = Palette.secondary)
                OutlinedTextField(
                    server,
                    {},
                    Modifier.fillMaxWidth(),
                    enabled = false,
                    label = { Text("Server") },
                    singleLine = true,
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                )
                SecretField("Password", password, passwordError) { password = it }
                SecretField("Confirm password", confirmation, confirmError) { confirmation = it }
                error?.let { Text(it.message, color = Palette.accent) }
                Pill(
                    "Create account",
                    { attempt { app.acceptJoin(server, code, name.trim(), password) } },
                    Modifier.fillMaxWidth().height(48.dp),
                    primary = true,
                    enabled = canCreate,
                )
                Text(
                    "This device will show your household. You can add kids after you sign in.",
                    Modifier.fillMaxWidth(),
                    color = Palette.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A password field that shows [error] under it. */
@Composable
internal fun SecretField(label: String, value: String, error: String?, onChange: (String) -> Unit) =
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
