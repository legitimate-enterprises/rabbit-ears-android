package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.AppModel
import enterprises.legitimate.rabbitears.kit.Invite
import enterprises.legitimate.rabbitears.kit.addAdult
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** The owner adds an adult, then hands over the code or link it returns. */
@Composable
fun AddAdult(app: AppModel, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<Invite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    invite?.let {
        return InviteDialog(name.trim(), it, close)
    }
    AlertDialog(
        close,
        {
            TextButton(
                {
                    busy = true
                    scope.launch {
                        try {
                            invite = app.addAdult(name.trim(), email.trim())
                        } catch (e: AppError) {
                            error = e.message
                        }
                        busy = false
                    }
                },
                enabled = !busy && name.isNotBlank() && '@' in email,
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
        title = { Text("Add an adult") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "They get their own sign-in to this household and can look after the kids.",
                    color = Palette.secondary,
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                )
                EmailField(email) { email = it }
                error?.let { Text(it, color = Palette.accent) }
            }
        },
        containerColor = Palette.raised,
    )
}
