package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import enterprises.legitimate.rabbitears.api.models.PlaybackSession
import enterprises.legitimate.rabbitears.kit.Handoff
import enterprises.legitimate.rabbitears.kit.LiveFeed
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun HandoffDialog(
    there: PlaybackSession,
    move: () -> Unit,
    watch: () -> Unit,
    cancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = cancel,
        confirmButton = { TextButton(move) { Text("Move here") } },
        dismissButton = {
            Row {
                TextButton(watch) { Text("Watch together") }
                TextButton(cancel) { Text("Cancel") }
            }
        },
        title = { Text(Handoff.title(there)) },
        containerColor = Palette.raised,
    )
}

/** The app's brief notice, as "Moved to Living Room", over whatever is up for a few seconds. */
@Composable
fun BoxScope.Notice(live: LiveFeed) {
    val text = live.notice ?: return
    LaunchedEffect(text) {
        delay(4_000)
        live.notice = null
    }
    Text(
        text,
        Modifier.align(Alignment.BottomCenter)
            .padding(bottom = 96.dp)
            .background(Color.Black.copy(alpha = 0.8f), CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        fontWeight = FontWeight.SemiBold,
    )
}
