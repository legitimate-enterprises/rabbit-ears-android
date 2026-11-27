package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.WatchTogetherRole
import enterprises.legitimate.rabbitears.kit.WatchTogetherModel
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Polls while the screen shows and the app is in view, and opens the player when the other member
 * starts playback. In the background nothing is asked.
 */
@Composable
fun rememberTogether(itemId: Long, title: String? = null): WatchTogetherModel? {
    val nav = LocalNav.current
    val together = nav.together(itemId) ?: return null
    val scope = rememberCoroutineScope()
    SideEffect { together.onStart = { position -> nav.play(itemId, position, title = title) } }
    LifecycleStartEffect(together) {
        val polling = scope.launch { together.run() }
        onStopOrDispose { polling.cancel() }
    }
    // An invitation opened or ended shows at once, not at the next poll.
    val heard = nav.app.live.together
    LaunchedEffect(together, heard) {
        if (heard > 0 && together.current == null) together.refresh()
    }
    return together
}

/** The wall clock, ticking each second for the m:ss countdowns. */
@Composable
private fun ticking(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun TogetherAction(together: WatchTogetherModel) {
    val scope = rememberCoroutineScope()
    val now = ticking()
    val waiting = together.waitingLabel(now)
    val join = together.joinLabel(now)
    when {
        together.inSync -> SyncChip(together.partnerName) { scope.launch { together.leave() } }
        waiting != null ->
            Row(
                Modifier.height(40.dp)
                    .background(Palette.indicator, RoundedCornerShape(20.dp))
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).background(Palette.accent, CircleShape))
                Text(waiting, color = Palette.accent, fontWeight = FontWeight.Medium)
                IconAction(
                    R.drawable.ic_close,
                    "Stop waiting",
                    { scope.launch { together.leave() } },
                )
            }
        join != null ->
            OutlinedButton(
                { scope.launch { together.join() } },
                Modifier.height(40.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Palette.accent),
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
            ) {
                Icon(painterResource(R.drawable.ic_people), null, Modifier.size(18.dp))
                Text(join, Modifier.padding(start = 8.dp), fontWeight = FontWeight.Medium)
            }
        together.current == null ->
            IconAction(
                R.drawable.ic_people,
                "Watch together",
                { scope.launch { together.open() } },
                tonal = true,
            )
    }
}

@Composable
fun TogetherNote(together: WatchTogetherModel, title: String) {
    val now = ticking()
    val partner = together.partner
    val host = together.invite?.members?.firstOrNull { it.role == WatchTogetherRole.host }?.name
    val text =
        when {
            together.inSync && partner != null ->
                "Pause and play on either device apply to both. ${partner.name} is watching " +
                    "on ${partner.deviceName}."
            together.waitingLabel(now) != null ->
                "Someone else who opens $title and taps Watch together in the next 5 minutes " +
                    "will play in sync with you. Pause and play on either device apply to both."
            together.joinLabel(now) != null && host != null ->
                "$host wants to watch this together. Join in the next " +
                    "${together.timeLeft(now)} to play in sync."
            else -> null
        }
    (together.error ?: text)?.let {
        Text(it, color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
fun SyncChip(name: String, onLeave: () -> Unit) {
    Row(
        Modifier.height(40.dp)
            .background(Palette.ok.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_people), null, Modifier.size(18.dp), tint = Palette.ok)
        Text(
            "In sync with $name",
            color = Palette.ok,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
        Text(
            "Leave",
            Modifier.background(Palette.tonal, RoundedCornerShape(16.dp))
                .clickable(onClick = onLeave)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
