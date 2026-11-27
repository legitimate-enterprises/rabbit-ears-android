package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Pull to refresh over a screen's scrolling [content]. [reload] reads what the screen shows again
 * and returns once it is applied, which the indicator waits for; the content stays as it is
 * meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    reload: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val app = LocalNav.current.app
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        refreshing,
        {
            refreshing = true
            scope.launch {
                try {
                    app.refresh(reload)
                } finally {
                    refreshing = false
                }
            }
        },
        modifier.fillMaxSize(),
        state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state,
                refreshing,
                Modifier.align(Alignment.TopCenter),
                containerColor = Palette.raised,
                color = Palette.accent,
            )
        },
        content = content,
    )
}

/**
 * A screen's value, read when a key changes and again on [reload]. A failed read, which is null,
 * leaves what was read before.
 */
class Loaded<T> {
    var value by mutableStateOf<T?>(null)
        internal set

    internal var read: suspend () -> T? = { null }

    suspend fun reload() {
        read()?.let { value = it }
    }
}

@Composable
fun <T> rememberLoaded(vararg keys: Any?, read: suspend () -> T?): Loaded<T> {
    val loaded = remember { Loaded<T>() }
    loaded.read = read
    LaunchedEffect(*keys) { loaded.reload() }
    return loaded
}
