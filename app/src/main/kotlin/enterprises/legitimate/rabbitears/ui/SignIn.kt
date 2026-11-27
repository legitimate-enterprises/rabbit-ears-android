package enterprises.legitimate.rabbitears.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.Discovery
import enterprises.legitimate.rabbitears.FoundServer
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.AppModel
import enterprises.legitimate.rabbitears.kit.ServerEndpoint
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** Sign in, or join with the invite the owner of the household gave. */
@Composable
fun SignIn(app: AppModel) {
    var joining by rememberSaveable { mutableStateOf(false) }
    if (joining) return Join(app) { joining = false }
    var server by rememberSaveable {
        mutableStateOf(ServerEndpoint.parse(app.lastServer)?.typed.orEmpty())
    }
    var email by rememberSaveable { mutableStateOf("") }
    // Saved instance state may be written to disk by the system; a password stays in memory.
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<FoundServer>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var allowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 37) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            allowed = true
        }
    LaunchedEffect(Unit) {
        // From Android 17 the local network is a runtime permission; browsing needs it.
        if (Build.VERSION.SDK_INT >= 37) ask.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
    if (allowed)
        DisposableEffect(Unit) {
            val discovery =
                Discovery(context) { server ->
                    if (found.none { it.endpoint == server.endpoint }) found += server
                }
            discovery.start()
            onDispose { discovery.stop() }
        }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                app.signIn(server, email, password)
            } catch (e: AppError) {
                error = e.message
            }
            busy = false
        }
    }

    AccountPage(app.placeName(server)) {
        Text(
            "Sign in to ${app.placeName(server)}",
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedTextField(
            server,
            { server = it },
            Modifier.fillMaxWidth(),
            label = { Text("Server") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        if (found.isNotEmpty())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                found.forEach { s ->
                    val text = s.endpoint.base.removePrefix("http://")
                    Chip(s.name, server == text) { server = text }
                }
            }
        OutlinedTextField(
            email,
            { email = it },
            Modifier.fillMaxWidth(),
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { submit() }),
        )
        error?.let { Text(it, color = Palette.accent) }
        Pill(
            if (busy) "Signing in…" else "Sign in",
            ::submit,
            Modifier.fillMaxWidth().height(48.dp),
            primary = true,
            enabled = !busy && server.isNotBlank() && email.isNotBlank() && password.isNotEmpty(),
        )
        TextButton({ joining = true }, Modifier.fillMaxWidth()) {
            Text("Have an invite code?", color = Palette.accent)
        }
        Text(
            "The owner of your household adds you. Sign in with the email and password you chose.",
            Modifier.fillMaxWidth(),
            color = Palette.secondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** The sign-in boards' column: the place's name under the logo, then [content]. */
@Composable
internal fun AccountPage(name: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_logo),
                    null,
                    Modifier.size(24.dp),
                    tint = Palette.accent,
                )
                Text(
                    name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}
