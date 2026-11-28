package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.changePassword
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** Preferences' Password: the current one, then a new one twice. */
@Composable
fun ChangePassword(close: () -> Unit) {
    val app = LocalNav.current.app
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val newError = "Use at least 12 characters.".takeIf { new.isNotEmpty() && new.length < 12 }
    val confirmError =
        "Passwords don't match.".takeIf { confirmation.isNotEmpty() && confirmation != new }
    val canSave = !busy && current.isNotEmpty() && new.length >= 12 && new == confirmation
    AlertDialog(
        close,
        {
            TextButton(
                {
                    busy = true
                    scope.launch {
                        try {
                            app.changePassword(current, new)
                            close()
                        } catch (e: AppError) {
                            error = e.message
                        }
                        busy = false
                    }
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretField("Current password", current, null) { current = it }
                SecretField("New password", new, newError) { new = it }
                SecretField("Confirm new password", confirmation, confirmError) {
                    confirmation = it
                }
                error?.let { Text(it, color = Palette.accent) }
            }
        },
        containerColor = Palette.raised,
    )
}
