package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.api.models.AccountDeletion
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.accountDeletion
import enterprises.legitimate.rabbitears.kit.allowed
import enterprises.legitimate.rabbitears.kit.deleteAccount
import enterprises.legitimate.rabbitears.kit.summary
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Preferences' Delete account: a confirmation that says what goes and asks the password again; the
 * app then returns to sign-in.
 */
@Composable
fun DeleteAccount() {
    var asking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Delete account", style = MaterialTheme.typography.titleMedium)
        Text("Removes your account and what is yours from this server.", color = Palette.secondary)
        Pill("Delete account…", { asking = true })
    }
    if (asking) Confirm { asking = false }
}

@Composable
private fun Confirm(close: () -> Unit) {
    val app = LocalNav.current.app
    val scope = rememberCoroutineScope()
    var deletion by remember { mutableStateOf<AccountDeletion?>(null) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { app.accountDeletion() }
            .onSuccess { deletion = it }
            .onFailure { error = AppError.from(it, AppError.SignedOut).message }
    }
    val allowed = deletion?.allowed == true
    AlertDialog(
        close,
        {
            if (allowed)
                TextButton(
                    {
                        busy = true
                        scope.launch {
                            runCatching { app.deleteAccount(password) }
                                .onFailure { error = AppError.from(it, AppError.SignedOut).message }
                            busy = false
                        }
                    },
                    enabled = !busy && password.isNotEmpty(),
                ) {
                    Text("Delete account")
                }
        },
        dismissButton = {
            TextButton(close) { Text(if (deletion?.allowed == false) "OK" else "Cancel") }
        },
        title = { Text("Delete your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                deletion?.let { Text(it.summary) }
                if (allowed)
                    OutlinedTextField(
                        password,
                        { password = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                error?.let { Text(it, color = Palette.accent) }
            }
        },
        containerColor = Palette.raised,
    )
}
