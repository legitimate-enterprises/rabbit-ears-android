package enterprises.legitimate.rabbitears.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import enterprises.legitimate.rabbitears.ExoOutput
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.SystemCapabilities
import enterprises.legitimate.rabbitears.api.models.ItemKind
import enterprises.legitimate.rabbitears.api.models.PlaybackSession
import enterprises.legitimate.rabbitears.api.models.Station
import enterprises.legitimate.rabbitears.api.models.StationStatus
import enterprises.legitimate.rabbitears.api.models.Verdict
import enterprises.legitimate.rabbitears.kit.Catalog
import enterprises.legitimate.rabbitears.kit.Editions
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.Handoff
import enterprises.legitimate.rabbitears.kit.PlayerModel
import enterprises.legitimate.rabbitears.kit.QualityChoice
import enterprises.legitimate.rabbitears.kit.StationTuner
import enterprises.legitimate.rabbitears.kit.Stations
import enterprises.legitimate.rabbitears.kit.UP_NEXT_SECONDS
import enterprises.legitimate.rabbitears.kit.UpNextPlay
import enterprises.legitimate.rabbitears.kit.answer
import enterprises.legitimate.rabbitears.kit.local
import enterprises.legitimate.rabbitears.kit.nextEpisode
import enterprises.legitimate.rabbitears.kit.upNext as askUpNext
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Outlives the player screen, so starting and ending a session finish after it closes. */
private val sessions = MainScope()

private enum class Sheet {
    Quality,
    Subtitles,
}

/** A new route builds a new session. */
@Composable fun Player(route: Route.Player) = key(route) { PlayerScreen(route) }

@Composable
private fun PlayerScreen(route: Route.Player) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val output = remember { ExoOutput(context) }
    val capabilities = remember { SystemCapabilities(context) }
    val model = remember {
        val edition = route.run?.editionId ?: route.editionId
        val store = nav.downloads?.store
        // The copy on the device stands in only for the edition asked for.
        val local =
            store?.local(nav.catalog.profileId, route.itemId)?.takeIf {
                edition == null || it.editionId == edition
            }
        PlayerModel(
            nav.catalog.api,
            nav.catalog.profileId,
            route.itemId,
            output,
            capabilities,
            scope,
            route.run?.playlistId,
            edition,
            local = local,
            preferLocal = store?.settings(nav.catalog.profileId)?.preferLocal == true,
            offline = nav.catalog.offline,
        )
    }
    val catalog = nav.catalog.catalog
    val item = catalog.item(route.itemId)
    // A playlist's next row stands in for the show's next episode.
    val upcoming = route.run?.next()
    val next =
        remember(catalog) { if (route.run == null) catalog.nextEpisode(route.itemId)?.id else null }
    val nextId = upcoming?.itemId ?: next
    // In sync, play, pause and seek go through Watch together, which then moves this player.
    val together = nav.together(route.itemId)
    val synced = together?.inSync == true
    val ready = model.stream != null
    LaunchedEffect(synced, ready) { if (synced && ready) together?.attach(output) }
    // Polls while waiting too, once this device asked another of the profile's to watch together.
    var handed by remember { mutableStateOf(false) }
    LaunchedEffect(synced, handed) { if (synced || handed) together?.run() }
    DisposableEffect(together) { onDispose { together?.detach() } }
    val playNext = {
        nextId?.let {
            nav.back()
            nav.play(it, run = upcoming)
        }
    }
    val begin = { startMs: Long ->
        sessions.launch {
            nav.music?.yieldToVideo()
            model.start(startMs, route.audioTrackId)
        }
    }
    // A title still playing on another device of the profile is asked about before it plays here.
    var there by remember { mutableStateOf<PlaybackSession?>(null) }
    DisposableEffect(Unit) {
        scope.launch {
            val found =
                if (together?.current == null && !nav.app.offline)
                    Handoff.elsewhere(nav.catalog.api, route.itemId)
                else null
            if (!isActive) return@launch
            if (found == null) begin(route.startMs) else there = found
        }
        onDispose {
            capabilities.close()
            sessions.launch {
                // Played from the device while the server answers: it hears of it now.
                if (model.stop() && !nav.app.offline)
                    runCatching { nav.downloads?.pushWatch(nav.catalog.api, nav.catalog.profileId) }
                nav.catalog.refreshWatchState(route.itemId)
            }
        }
    }
    there?.let { session ->
        HandoffDialog(
            session,
            move = {
                there = null
                scope.launch {
                    val at = Handoff.move(nav.catalog.api, session)
                    if (!isActive) return@launch
                    if (at == null) nav.back() else begin(at)
                }
            },
            watch = {
                there = null
                scope.launch {
                    val opened = together?.open(session.id) == true
                    if (!isActive) return@launch
                    handed = opened
                    if (opened) begin(session.positionMs) else nav.back()
                }
            },
            cancel = {
                there = null
                nav.back()
            },
        )
    }
    // Another device of the profile may take this session with Move here, which closes the
    // player saying where it went, or ask it into a Watch together, which it joins.
    LaunchedEffect(Unit) {
        nav.app.live.events.collect { batch ->
            val session = model.stream?.sessionId ?: return@collect
            if (Handoff.moved(batch, session)) {
                val device = Handoff.movedTo(nav.catalog.api, session)
                if (!isActive) return@collect
                model.taken()
                nav.app.live.notice = "Moved to $device"
                nav.back()
            }
            Handoff.asked(batch, session)?.let { together?.join(it, output.positionMs) }
        }
    }
    var upNext by remember { mutableStateOf<UpNextPlay?>(null) }
    var autoplay by remember { mutableStateOf(nav.app.activeProfile?.autoplay != false) }
    val playUpNext = { next: UpNextPlay ->
        nav.back()
        nav.play(next.itemId, title = next.title, run = next.run, editionId = next.editionId)
    }
    // At the end Up next offers what follows; with nothing next, or in a Watch together, it closes.
    output.onEnded = {
        if (together?.current != null) nav.back()
        else
            scope.launch {
                val next = runCatching {
                    nav.catalog.offline.answer({ upNext(route.itemId, route.run) }) {
                        nav.catalog.api.askUpNext(nav.catalog.profileId, route.itemId, route.run)
                    }
                }
                    .getOrNull()
                if (next == null) nav.back() else upNext = next
            }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        output.pause()
        sessions.launch { model.reportPosition() }
    }
    val view = LocalView.current
    SideEffect { view.keepScreenOn = output.playing }
    activity?.let { FullScreen(it) }
    val pip = activity?.let { rememberPictureInPicture(it) } == true
    val pipActivity = activity?.takeIf(::canPip)
    var bounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(output.aspect, bounds, output.playing) {
        runCatching {
            pipActivity?.setPictureInPictureParams(pipParams(output.aspect, bounds, output.playing))
        }
    }
    DisposableEffect(pipActivity) {
        onDispose {
            runCatching { pipActivity?.setPictureInPictureParams(pipParams(1f, null, false)) }
        }
    }

    var shown by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var scrubbing by remember { mutableStateOf<Long?>(null) }
    BackHandler(sheet != null) { sheet = null }
    var touches by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    val poke = {
        shown = true
        touches++
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = output.positionMs
            duration = output.durationMs
            delay(250)
        }
    }
    LaunchedEffect(touches, sheet, scrubbing, output.playing) {
        delay(3_000)
        if (sheet == null && scrubbing == null && output.playing) shown = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { SurfaceView(it).also(output.player::setVideoSurfaceView) },
            modifier =
                Modifier.align(Alignment.Center).aspectRatio(output.aspect).onGloballyPositioned {
                    bounds = it.boundsInWindow().toAndroidRect()
                },
        )
        Box(
            Modifier.fillMaxSize().clickable(
                remember { MutableInteractionSource() },
                null,
                onClickLabel = "Show controls",
            ) {
                if (sheet != null) sheet = null else if (shown) shown = false else poke()
            },
        )
        model.cue(now)?.let {
            Text(
                it,
                Modifier.align(Alignment.BottomCenter)
                    .padding(
                        bottom = if (shown && !pip) 120.dp else 32.dp,
                        start = 24.dp,
                        end = 24.dp,
                    )
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White,
                fontSize = if (pip) 11.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        if (shown && !pip) {
            Controls(
                model = model,
                output = output,
                title =
                    Editions.titled(
                        item?.let { catalog.show(it) ?: it }?.let(Catalog::title)
                            ?: route.title.orEmpty(),
                        route.run?.editionName,
                    ),
                subtitle =
                    item
                        ?.takeIf { it.kind == ItemKind.episode }
                        ?.let { "${Format.episodeCode(it)} · ${Catalog.title(it)}" },
                now = scrubbing ?: now,
                duration = duration,
                scrubbing = scrubbing,
                hasNext = nextId != null,
                onBack = { nav.back() },
                onPip =
                    pipActivity?.let {
                        {
                            runCatching {
                                it.enterPictureInPictureMode(pipParams(output.aspect, bounds, true))
                            }
                        }
                    },
                onSheet = {
                    sheet = it
                    poke()
                },
                onScrub = {
                    scrubbing = it
                    poke()
                },
                sync =
                    together
                        ?.takeIf { synced }
                        ?.let { t -> { SyncChip(t.partnerName) { scope.launch { t.leave() } } } },
                onCommit = {
                    if (synced) scope.launch { together?.seek(it) } else output.seek(it)
                    now = it
                    scrubbing = null
                    poke()
                },
                onToggle = {
                    if (synced)
                        scope.launch { if (output.playing) together?.pause() else together?.play() }
                    else if (output.playing) {
                        output.pause()
                        scope.launch { model.reportPosition() }
                    } else output.play()
                    poke()
                },
                onSkip = {
                    val to = maxOf(0, output.positionMs + it)
                    if (synced) scope.launch { together?.seek(to) } else output.seek(to)
                    poke()
                },
                onNext = { playNext() },
            )
        }
        if (shown && !pip)
            together?.pausedNotice?.let {
                Text(
                    it,
                    Modifier.align(Alignment.Center)
                        .offset(y = (-68).dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        upNext?.let { next ->
            UpNextCard(
                next.title,
                autoplay,
                onAutoplay = { on ->
                    autoplay = on
                    nav.app.activeProfile?.let { me ->
                        scope.launch { runCatching { nav.app.setPreferences(me, autoplay = on) } }
                    }
                },
                onPlay = { playUpNext(next) },
                onCancel = { nav.back() },
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
            )
        }
        if (!pip)
            when (sheet) {
                Sheet.Quality -> QualitySheet(model, capabilities, scope) { sheet = null }
                Sheet.Subtitles -> SubtitleSheet(model, scope) { sheet = null }
                null -> Unit
            }
        model.error?.let { ErrorCard(it) { nav.back() } }
    }
}

@Composable
private fun Controls(
    model: PlayerModel,
    output: ExoOutput,
    title: String,
    subtitle: String?,
    now: Long,
    duration: Long,
    scrubbing: Long?,
    hasNext: Boolean,
    sync: (@Composable () -> Unit)?,
    onBack: () -> Unit,
    onPip: (() -> Unit)?,
    onSheet: (Sheet) -> Unit,
    onScrub: (Long?) -> Unit,
    onCommit: (Long) -> Unit,
    onToggle: () -> Unit,
    onSkip: (Long) -> Unit,
    onNext: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.6f),
                    0.24f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.78f),
                ),
            ),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconAction(R.drawable.ic_back, "Back", onBack)
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 24.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(it, color = Palette.secondary, fontSize = 13.sp, maxLines = 1)
                    }
                }
                sync?.invoke()
                onPip?.let { IconAction(R.drawable.ic_pip, "Picture in picture", it) }
            }
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(44.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Skip(R.drawable.ic_replay, "Back 10 seconds") { onSkip(-10_000) }
                IconButton(
                    onToggle,
                    Modifier.size(72.dp),
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = Palette.tag,
                            contentColor = Palette.text,
                        ),
                ) {
                    Icon(
                        painterResource(
                            if (output.playing) R.drawable.ic_pause else R.drawable.ic_play,
                        ),
                        if (output.playing) "Pause" else "Play",
                        Modifier.size(40.dp),
                    )
                }
                Skip(R.drawable.ic_forward, "Forward 10 seconds") { onSkip(10_000) }
            }
            Column(
                Modifier.padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val clock = Color(0xFFE5E5EA)
                    Text(Format.clock(now / 1000), color = clock, fontSize = 13.sp)
                    ScrubBar(
                        model,
                        now,
                        duration,
                        scrubbing,
                        onScrub,
                        onCommit,
                        Modifier.weight(1f),
                    )
                    Text(
                        "-" + Format.clock((duration - now) / 1000),
                        color = clock,
                        fontSize = 13.sp,
                    )
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    QualityPill(model, Modifier.align(Alignment.Center)) { onSheet(Sheet.Quality) }
                    Row(Modifier.align(Alignment.CenterEnd)) {
                        IconAction(
                            R.drawable.ic_subtitles,
                            "Subtitles",
                            { onSheet(Sheet.Subtitles) },
                        )
                        if (hasNext) IconAction(R.drawable.ic_next, "Next", onNext)
                    }
                }
            }
        }
    }
}

@Composable
private fun Skip(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(
        onClick,
        Modifier.size(56.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Palette.text),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), label, Modifier.size(36.dp))
            Text("10", Modifier.offset(y = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QualityPill(model: PlayerModel, modifier: Modifier, onClick: () -> Unit) {
    val pill = model.pill ?: return
    Row(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClickLabel = "Playback quality", onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (model.adjusting) Text("Adjusting…", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        else {
            pill.tags.forEach { Tag(it) }
            Text(pill.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(pill.rate, color = Palette.secondary, fontSize = 13.sp)
        }
        Icon(painterResource(R.drawable.ic_chevron_up), null, Modifier.size(14.dp))
    }
}

@Composable
private fun ScrubBar(
    model: PlayerModel,
    now: Long,
    duration: Long,
    scrubbing: Long?,
    onScrub: (Long?) -> Unit,
    onCommit: (Long) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.height(24.dp), contentAlignment = Alignment.CenterStart) {
        val width = constraints.maxWidth.toFloat()
        val fraction = if (duration > 0) (now.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val at = { x: Float -> ((x / width).coerceIn(0f, 1f) * duration).toLong() }
        Box(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = "Seek"
                    stateDescription = Format.clock(now / 1000)
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target ->
                        onCommit((target.coerceIn(0f, 1f) * duration).toLong())
                        true
                    }
                }
                .pointerInput(duration, width) { detectTapGestures { onCommit(at(it.x)) } }
                .pointerInput(duration, width) {
                    var target = 0L
                    detectHorizontalDragGestures(
                        onDragStart = {
                            target = at(it.x)
                            onScrub(target)
                        },
                        onDragEnd = { onCommit(target) },
                        onDragCancel = { onScrub(null) },
                    ) { change, _ ->
                        target = at(change.position.x)
                        onScrub(target)
                    }
                },
        )
        Box(
            Modifier.fillMaxWidth()
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.25f), CircleShape),
        )
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Palette.accent, CircleShape))
        val density = LocalDensity.current
        val knob = with(density) { (fraction * width).roundToInt() - 8.dp.roundToPx() }
        Box(
            Modifier.offset { IntOffset(knob, 0) }
                .size(16.dp)
                .background(Palette.accent, CircleShape),
        )
        if (scrubbing != null) {
            model.previewPath(scrubbing)?.let { path ->
                val card = 176.dp
                val cardPx = with(density) { card.toPx() }
                val center =
                    (fraction * width).coerceIn(cardPx / 2, maxOf(cardPx / 2, width - cardPx / 2))
                PreviewCard(
                    path,
                    Format.clock(scrubbing / 1000),
                    model.chapter(scrubbing),
                    card,
                    Modifier.offset { IntOffset((center - cardPx / 2).roundToInt(), 0) }
                        .offset(y = (-136).dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    path: String,
    time: String,
    chapter: String?,
    width: Dp,
    modifier: Modifier,
) {
    Column(
        modifier.width(width).clip(MaterialTheme.shapes.small).background(Palette.raised),
    ) {
        Art(path, Palette.ground, Modifier.fillMaxWidth().aspectRatio(16f / 9))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(time, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            chapter?.let { Text(it, color = Palette.secondary, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun SideSheet(
    title: String,
    heading: String?,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
    ) {
        Column(
            Modifier.align(Alignment.CenterEnd)
                .fillMaxHeight()
                .widthIn(max = 368.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
                .background(Palette.raised)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .safeDrawingPadding()
                .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.padding(start = 12.dp).heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    heading?.let { Text(it, color = Palette.secondary, fontSize = 12.sp) }
                }
                IconAction(R.drawable.ic_close, "Close", onClose)
            }
            content()
        }
    }
}

@Composable
private fun SheetRow(
    title: String,
    detail: String?,
    selected: Boolean,
    trailing: String? = null,
    trailingColor: Color = Palette.secondary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp)) {
            if (selected)
                Icon(painterResource(R.drawable.ic_check), "Selected", tint = Palette.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            detail?.let {
                Text(it, color = Palette.secondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        trailing?.let {
            Text(it, color = trailingColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** [scope] is the screen's: a pick closes the sheet before its request returns. */
@Composable
private fun QualitySheet(
    model: PlayerModel,
    capabilities: SystemCapabilities,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val heading =
        remember(model.stream?.away) {
            Format.qualityHeading(model.stream?.away == true, capabilities.facts().path)
        }
    SideSheet("Quality", heading, onClose) {
        model.qualityOptions.forEach { option ->
            SheetRow(
                option.name,
                option.detail,
                option.selected,
                option.trailing,
                when {
                    option.blocked -> Palette.secondary
                    option.verdict == Verdict.direct_play -> Palette.ok
                    else -> Palette.accent
                },
                enabled = !option.blocked,
            ) {
                onClose()
                scope.launch { model.selectQuality(option) }
            }
            if (option.choice == QualityChoice.Auto)
                HorizontalDivider(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Palette.tonal,
                )
        }
    }
}

/** [scope] is the screen's, as for [QualitySheet]. */
@Composable
private fun SubtitleSheet(model: PlayerModel, scope: CoroutineScope, onClose: () -> Unit) {
    SideSheet("Subtitles", null, onClose) {
        model.subtitleOptions.forEach { option ->
            SheetRow(option.name, option.detail, option.choice == model.subtitleChoice) {
                onClose()
                scope.launch { model.selectSubtitles(option.choice) }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), Alignment.Center) {
        Column(
            Modifier.padding(24.dp)
                .background(Palette.raised, MaterialTheme.shapes.large)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Pill("Back", onBack)
        }
    }
}

/** A phone's player is one landscape layout; a tablet plays either way up. */
@Composable
private fun FullScreen(activity: Activity) {
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        val orientation = activity.requestedOrientation
        if (activity.resources.configuration.smallestScreenWidthDp < 600)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = orientation
        }
    }
    val view = LocalView.current
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }
}

private fun canPip(activity: Activity) =
    activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** The platform keeps the picture's shape within 1:2.39 to 2.39:1. */
private fun pipParams(aspect: Float, bounds: Rect?, auto: Boolean): PictureInPictureParams {
    val ratio = aspect.coerceIn(1 / 2.39f, 2.39f)
    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        .setSourceRectHint(bounds)
        .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(auto) }
        .build()
}

@Composable
private fun rememberPictureInPicture(activity: Activity): Boolean {
    var pip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val component = activity as? ComponentActivity
        val listener =
            Consumer<PictureInPictureModeChangedInfo> { pip = it.isInPictureInPictureMode }
        component?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { component?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return pip
}

/** A new route tunes in afresh. */
@Composable fun StationPlayer(route: Route.Tune) = key(route) { StationPlayerScreen(route.station) }

/** A station plays at its live position: no pause, seek, skip or next, and no watch state. */
@Composable
private fun StationPlayerScreen(station: Station) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val output = remember { ExoOutput(context) }
    val capabilities = remember { SystemCapabilities(context) }
    val tuner = remember { StationTuner(nav.catalog.api, station, scope) }
    val model = remember {
        PlayerModel(
            nav.catalog.api,
            nav.catalog.profileId,
            "",
            output,
            capabilities,
            scope,
            station = tuner,
        )
    }
    DisposableEffect(Unit) {
        sessions.launch {
            nav.music?.yieldToVideo()
            model.start(0)
        }
        onDispose {
            capabilities.close()
            sessions.launch { model.stop() }
        }
    }
    output.onEnded = { scope.launch { model.programEnded() } }
    // Away from the screen the station is left; coming back joins it where it is then.
    var away by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        away = true
        sessions.launch { model.standBy() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (away) {
            away = false
            scope.launch { model.start(0) }
        }
    }
    val view = LocalView.current
    SideEffect { view.keepScreenOn = output.playing }
    activity?.let { FullScreen(it) }
    val pip = activity?.let { rememberPictureInPicture(it) } == true
    val pipActivity = activity?.takeIf(::canPip)
    var bounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(output.aspect, bounds, output.playing) {
        runCatching {
            pipActivity?.setPictureInPictureParams(pipParams(output.aspect, bounds, output.playing))
        }
    }
    DisposableEffect(pipActivity) {
        onDispose {
            runCatching { pipActivity?.setPictureInPictureParams(pipParams(1f, null, false)) }
        }
    }

    var shown by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    BackHandler(sheet != null) { sheet = null }
    var touches by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    val poke = {
        shown = true
        touches++
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = if (model.stream?.live == true) tuner.elapsedMs() else output.positionMs
            delay(250)
        }
    }
    LaunchedEffect(touches, sheet, output.playing) {
        delay(3_000)
        if (sheet == null && output.playing) shown = false
    }
    val onAir = tuner.onAir
    val lines = Stations.caption(onAir, station.kind)
    val onPip = pipActivity?.let {
        {
            runCatching { it.enterPictureInPictureMode(pipParams(output.aspect, bounds, true)) }
            Unit
        }
    }
    val topBar =
        @Composable { subtitle: String? ->
            Row(
                Modifier.safeDrawingPadding().padding(horizontal = 16.dp).padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconAction(R.drawable.ic_back, "Back", { nav.back() })
                Column(Modifier.weight(1f)) {
                    Text(
                        station.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(it, color = Palette.secondary, fontSize = 13.sp, maxLines = 1)
                    }
                }
                onPip?.let { IconAction(R.drawable.ic_pip, "Picture in picture", it) }
            }
        }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { SurfaceView(it).also(output.player::setVideoSurfaceView) },
            modifier =
                Modifier.align(Alignment.Center).aspectRatio(output.aspect).onGloballyPositioned {
                    bounds = it.boundsInWindow().toAndroidRect()
                },
        )
        // A live stream is on between programs too, with the station's ident.
        val live = model.stream?.live == true
        if (model.stream == null || !live && onAir.status != StationStatus.on_air) {
            StationIdentity(
                station.name,
                Modifier.fillMaxSize().background(Palette.ground),
                big = !pip,
                imageUrl = station.imageUrl,
            )
            if (!pip) {
                Text(
                    lines.first(),
                    Modifier.align(Alignment.Center).padding(top = 150.dp),
                    color = Palette.secondary,
                )
                topBar(null)
            }
            model.error?.let { ErrorCard(it) { nav.back() } }
            return@Box
        }
        Box(
            Modifier.fillMaxSize().clickable(
                remember { MutableInteractionSource() },
                null,
                onClickLabel = "Show controls",
            ) {
                if (sheet != null) sheet = null else if (shown) shown = false else poke()
            },
        )
        model.cue(now)?.let {
            Text(
                it,
                Modifier.align(Alignment.BottomCenter)
                    .padding(
                        bottom = if (shown && !pip) 120.dp else 32.dp,
                        start = 24.dp,
                        end = 24.dp,
                    )
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White,
                fontSize = if (pip) 11.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        if (shown && !pip) {
            val length = onAir.now?.durationMs ?: 0
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.6f),
                            0.24f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f),
                        ),
                    ),
            ) {
                topBar(lines.first())
                Column(
                    Modifier.align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        val clock = Color(0xFFE5E5EA)
                        val fraction =
                            if (length > 0) (now.toFloat() / length).coerceIn(0f, 1f) else 0f
                        Text(Format.clock(now / 1000), color = clock, fontSize = 13.sp)
                        Box(
                            Modifier.weight(1f)
                                .height(4.dp)
                                .background(Color.White.copy(alpha = 0.25f), CircleShape)
                                .semantics {
                                    contentDescription = "Progress"
                                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                                },
                        ) {
                            Box(
                                Modifier.fillMaxWidth(fraction)
                                    .height(4.dp)
                                    .background(Palette.accent, CircleShape),
                            )
                        }
                        Text(
                            "-" + Format.clock((length - now) / 1000),
                            color = clock,
                            fontSize = 13.sp,
                        )
                    }
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        QualityPill(model, Modifier.align(Alignment.Center)) {
                            sheet = Sheet.Quality
                            poke()
                        }
                        if (!live)
                            Row(Modifier.align(Alignment.CenterEnd)) {
                                IconAction(
                                    R.drawable.ic_subtitles,
                                    "Subtitles",
                                    {
                                        sheet = Sheet.Subtitles
                                        poke()
                                    },
                                )
                            }
                    }
                }
            }
        }
        if (!pip)
            when (sheet) {
                Sheet.Quality -> QualitySheet(model, capabilities, scope) { sheet = null }
                Sheet.Subtitles -> SubtitleSheet(model, scope) { sheet = null }
                null -> Unit
            }
        model.error?.let { ErrorCard(it) { nav.back() } }
    }
}

@Composable
private fun UpNextCard(
    title: String,
    autoplay: Boolean,
    onAutoplay: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var left by remember(autoplay) { mutableIntStateOf(UP_NEXT_SECONDS) }
    LaunchedEffect(autoplay) {
        if (!autoplay) return@LaunchedEffect
        while (left > 0) {
            delay(1_000)
            left--
        }
        onPlay()
    }
    Column(
        modifier
            .widthIn(max = 360.dp)
            .background(Palette.raised, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Up next: $title", fontWeight = FontWeight.SemiBold, maxLines = 2)
        if (autoplay) Text("Plays in ${left}s", color = Palette.secondary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Play now", onPlay, primary = true)
            Pill("Cancel", onCancel)
        }
        Row(
            Modifier.clickable { onAutoplay(!autoplay) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                autoplay,
                null,
                colors = CheckboxDefaults.colors(checkedColor = Palette.accent),
            )
            Text("Autoplay")
        }
    }
}
