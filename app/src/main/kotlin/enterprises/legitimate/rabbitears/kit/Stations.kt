package enterprises.legitimate.rabbitears.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import enterprises.legitimate.rabbitears.api.models.CapabilitySnapshot
import enterprises.legitimate.rabbitears.api.models.PlaybackStream
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.Station
import enterprises.legitimate.rabbitears.api.models.StationInput
import enterprises.legitimate.rabbitears.api.models.StationOnAir
import enterprises.legitimate.rabbitears.api.models.StationOrder
import enterprises.legitimate.rabbitears.api.models.StationPlaybackRequest
import enterprises.legitimate.rabbitears.api.models.StationProgram
import enterprises.legitimate.rabbitears.api.models.StationStatus
import enterprises.legitimate.rabbitears.api.models.StationTimeRange
import enterprises.legitimate.rabbitears.api.models.StationTiming
import enterprises.legitimate.rabbitears.api.models.StationVisibility
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The program's name: a movie with its edition, an episode after its show, a song. */
val StationProgram.title: String
    get() =
        song?.title
            ?: Editions.titled(
                listOfNotNull(show, item).joinToString(" · ") { Catalog.title(it) },
                editionName,
            )

/** The station lines tiles, pages and the player share; the client computes no schedule. */
object Stations {
    /** `4:00 PM` in the device's zone and locale. */
    fun time(at: OffsetDateTime, zone: ZoneId, locale: Locale): String =
        at.atZoneSameInstant(zone)
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

    /**
     * `On now: X` and `Next: Y at 4:00 PM`, `Up next at 4:00 PM: Y` between programs, or `Back
     * Friday at 10:00 PM` off schedule. A music station's next has no time: songs follow at once.
     */
    fun caption(
        onAir: StationOnAir,
        kind: PlaylistKind,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<String> {
        val next = onAir.next
        val nextLine = next?.let {
            if (kind == PlaylistKind.music) "Next: ${it.title}"
            else "Next: ${it.title} at ${time(it.startsAt, zone, locale)}"
        }
        val lines =
            when (onAir.status) {
                StationStatus.on_air ->
                    listOfNotNull(onAir.now?.let { "On now: ${it.title}" }, nextLine)
                StationStatus.between_programs ->
                    listOfNotNull(
                        next?.let { "Up next at ${time(it.startsAt, zone, locale)}: ${it.title}" },
                    )
                StationStatus.off_schedule ->
                    listOfNotNull(
                        next?.let {
                            val day =
                                it.startsAt
                                    .atZoneSameInstant(zone)
                                    .dayOfWeek
                                    .getDisplayName(TextStyle.FULL, locale)
                            "Back $day at ${time(it.startsAt, zone, locale)}"
                        },
                    )
            }
        return lines.ifEmpty { listOf("Nothing scheduled") }
    }

    /** Home's On now tile names a station with its kind: `Movies: Marvel`. */
    fun onNowName(station: Station): String =
        "${
            when (station.kind) {
                PlaylistKind.movies -> "Movies"
                PlaylistKind.tv -> "TV"
                PlaylistKind.music -> "Music"
            }
        }: ${station.name}"

    /** What a screen reader says of that tile, which tunes in: `Watch Movies: Marvel`. */
    fun onNowLabel(station: Station): String =
        "${if (station.kind == PlaylistKind.music) "Listen to" else "Watch"} ${onNowName(station)}"

    /**
     * How far through the program on, 0 to 1, as the server answered; null with none or no length.
     */
    fun onNowFraction(onAir: StationOnAir): Double? {
        val length = onAir.now?.durationMs?.takeIf { it > 0 } ?: return null
        val position = onAir.positionMs ?: return null
        return minOf(1.0, position.toDouble() / length)
    }

    /**
     * A new station's timing: the hour for movies, the half hour for tv, back to back for music.
     */
    fun timing(kind: PlaylistKind): StationTiming =
        when (kind) {
            PlaylistKind.movies -> StationTiming.hour
            PlaylistKind.tv -> StationTiming.half_hour
            PlaylistKind.music -> StationTiming.continuous
        }

    fun timingName(timing: StationTiming): String =
        when (timing) {
            StationTiming.continuous -> "Back to back"
            StationTiming.half_hour -> "On the hour or half hour"
            StationTiming.hour -> "On the hour"
        }

    /** Asked once the owner saves a station or changes what it plays. */
    const val RESCHEDULE_ANSWERS =
        "Now: what is on finishes, then the new schedule starts. Later: what is scheduled " +
            "plays out, and the change follows as the schedule is extended."
}

/**
 * The stations the active profile may see, each with what is on. Kids see what the server lists and
 * write nothing; the server answers their writes with 403.
 */
class StationsModel(val api: Api) {
    var stations by mutableStateOf<List<Station>>(emptyList())
        private set

    var loaded by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** The last save's refusals by request field, such as `name`. */
    var fieldErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun of(kind: PlaylistKind) = stations.filter { it.kind == kind }

    /**
     * The stations of [kind] that [profileId] owns and may add to: one on rules plays what they
     * match.
     */
    fun mine(kind: PlaylistKind, profileId: String) =
        of(kind).filter { it.ownerId == profileId && it.smart != true }

    /** A new private station of [kind], continuous and in order, with its own empty contents. */
    suspend fun create(name: String, kind: PlaylistKind): Station? =
        save(null, StationForm(kind = kind).also { it.name = name }.input)

    /** Home's On now row: every kind, only what is on the air. */
    val onNow: List<Station>
        get() = stations.filter { it.onAir.status == StationStatus.on_air }

    /** Reads one kind's stations, or every station without [kind]. */
    suspend fun load(kind: PlaylistKind? = null) {
        write {
            val fetched = pages { o ->
                api.call { stations.listStations(kind, PAGE, o ?: 0) }
                    .let { it.items to it.nextOffset }
            }
            stations = if (kind == null) fetched else stations.filter { it.kind != kind } + fetched
        }
        loaded = true
    }

    /** One station, fresh from the server, replacing its copy; null when unseen. */
    suspend fun get(id: String): Station? {
        var fresh: Station? = null
        write { fresh = api.call { stations.getStation(id) }.also(::replace) }
        return fresh
    }

    /** The programs of [date], by start; null when they could not be read. */
    suspend fun schedule(id: String, date: LocalDate): List<StationProgram>? {
        var programs: List<StationProgram>? = null
        write {
            programs = pages { o ->
                api.call { stations.listStationSchedule(id, date, PAGE, o ?: 0) }
                    .let { it.items to it.nextOffset }
            }
        }
        return programs
    }

    /** Creates a station, or replaces station [id]; null when refused, with [fieldErrors] set. */
    suspend fun save(id: String?, input: StationInput): Station? {
        var saved: Station? = null
        write {
            saved =
                api.call {
                        if (id == null) stations.createStation(input)
                        else stations.replaceStation(id, input)
                    }
                    .also(::replace)
        }
        return saved
    }

    suspend fun delete(station: Station) = write {
        api.call { stations.deleteStation(station.id) }
        stations = stations.filter { it.id != station.id }
    }

    suspend fun reschedule(station: Station): Station? {
        var fresh: Station? = null
        write { fresh = api.call { stations.rescheduleStation(station.id) }.also(::replace) }
        return fresh
    }

    private fun replace(fresh: Station) {
        stations =
            if (stations.any { it.id == fresh.id })
                stations.map { if (it.id == fresh.id) fresh else it }
            else stations + fresh
    }

    private suspend fun write(block: suspend () -> Unit): Boolean =
        try {
            block()
            error = null
            fieldErrors = emptyMap()
            true
        } catch (e: Exception) {
            error = AppError.from(e, AppError.SignedOut).message
            fieldErrors = ApiError.from(e)?.fields.orEmpty()
            false
        }

    companion object {
        const val PAGE = 200
    }
}

/** The station editor: values and the request they become. */
class StationForm(private val station: Station? = null, kind: PlaylistKind = PlaylistKind.movies) {
    var name by mutableStateOf(station?.name.orEmpty())
    /** Chosen when the station is made and fixed after. */
    var kind by mutableStateOf(station?.kind ?: kind)
    var order by mutableStateOf(station?.order ?: StationOrder.playlist)
    /** server is the admin's to set and shows read-only. */
    var visibility by mutableStateOf(station?.visibility ?: StationVisibility.private)
    var timing by mutableStateOf(station?.timing ?: Stations.timing(kind))
    var continuous by
        mutableStateOf(station == null || station.days.isEmpty() && station.times.isEmpty())
    var days by mutableStateOf(station?.days.orEmpty().toSet())
    var times by mutableStateOf(station?.times.orEmpty())

    val ready: Boolean
        get() = name.isNotBlank()

    /** A new station's kind, with that kind's timing. */
    fun choose(kind: PlaylistKind) {
        this.kind = kind
        timing = Stations.timing(kind)
    }

    /**
     * Never names a playlist: a new station makes its own and an old one keeps its. Continuous
     * sends empty days and times, since an absent field would keep the old ones.
     */
    val input: StationInput
        get() =
            StationInput(
                name.trim(),
                order = order,
                visibility =
                    when (visibility) {
                        StationVisibility.private -> StationInput.Visibility.`private`
                        StationVisibility.household -> StationInput.Visibility.household
                        // The server answers 403 to a visibility on a server station.
                        StationVisibility.server -> null
                    },
                timing = timing,
                days = if (continuous) emptySet() else days,
                times = if (continuous) emptyList() else times,
                kind = kind.takeIf { station == null },
            )

    fun addRange() {
        times = times + StationTimeRange("18:00", "22:00")
    }

    fun removeRange(index: Int) {
        times = times.filterIndexed { i, _ -> i != index }
    }
}

/**
 * Tunes in to one station and keeps it tuned: what is on plays at its live position, and when it
 * ends, or off the air when the next program is due, [tune]'s cue runs so the player asks again. A
 * live stream plays on through programs and idents, so there only what is on is read. Both players
 * share it.
 */
class StationTuner(
    private val api: Api,
    val station: Station,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** What is on, as the last answer said; off the air, why and what is next. */
    var onAir by mutableStateOf(station.onAir)
        private set

    private var timer: Job? = null
    private var due: Pair<OffsetDateTime, suspend () -> Unit>? = null

    /**
     * Asks for playback: the stream of what is on, or null when nothing is, with [onAir] saying
     * why. [cue] runs once at the program's end or, off the air, at resumesAt; with none scheduled,
     * never. [live] asks for the station's one continuous stream, which has no such end. Any other
     * refusal throws; one from a server that failed or could not be reached runs [again] in a few
     * seconds.
     */
    suspend fun tune(
        snapshot: CapabilitySnapshot,
        audioTrackId: String? = null,
        subtitleTrackId: String? = null,
        live: Boolean = false,
        again: suspend () -> Unit,
        cue: suspend () -> Unit,
    ): PlaybackStream? {
        cancel()
        val request =
            StationPlaybackRequest(snapshot, audioTrackId, subtitleTrackId, live.takeIf { it })
        val tuned =
            try {
                api.call { stations.startStationPlayback(station.id, request) }
            } catch (e: Exception) {
                val error = ApiError.from(e)
                if (error?.code != "off_air") {
                    if (e !is CancellationException && (error?.status ?: 500) >= 500)
                        at(clock().plusSeconds(5).atOffset(ZoneOffset.UTC), again)
                    throw e
                }
                // The refusal says only when; the station says whether between programs or off.
                onAir =
                    runCatching { api.call { stations.getStation(station.id) }.onAir }
                        .getOrNull()
                        ?.takeIf { it.status != StationStatus.on_air }
                        ?: StationOnAir(StationStatus.off_schedule, emptyList())
                error.resumesAt?.let { at(it, cue) }
                return null
            }
        onAir = tuned.onAir
        if (tuned.stream.live == true) follow() else tuned.onAir.now?.let { at(it.endsAt, cue) }
        return tuned.stream
    }

    /**
     * At the end of what is on, or the start of what is next, reads the station and waits again. A
     * station that cannot be read is read again in a minute.
     */
    private fun follow(time: OffsetDateTime? = onAir.now?.endsAt ?: onAir.next?.startsAt) {
        at(time ?: return) {
            val read = runCatching { api.call { stations.getStation(station.id) }.onAir }
            read.onSuccess { onAir = it }
            if (read.isSuccess) follow()
            else follow(clock().plusSeconds(60).atOffset(ZoneOffset.UTC))
        }
    }

    /** Returns once the program on at last word is over by its schedule. */
    suspend fun programOver() {
        onAir.now?.let { sleep(until(it.endsAt)) }
    }

    /** How far into its slot the program on is by the clock, which is where a live stream is. */
    fun elapsedMs(): Long =
        onAir.now?.let { maxOf(0, Duration.between(it.startsAt.toInstant(), clock()).toMillis()) }
            ?: 0

    /** At least a second, so a clock ahead of the server's never asks in a loop. */
    private fun until(time: OffsetDateTime) =
        maxOf(1_000, Duration.between(clock(), time.toInstant()).toMillis())

    /** Stops waiting; nothing more is asked. */
    fun cancel() {
        timer?.cancel()
        timer = null
        due = null
    }

    /**
     * Arms the wait again for the same instant, as the app comes back to the foreground, where a
     * wait the device slept through may come late.
     */
    fun resume() {
        due?.let { (time, cue) -> at(time, cue) }
    }

    private fun at(time: OffsetDateTime, cue: suspend () -> Unit) {
        timer?.cancel()
        due = time to cue
        val wait = until(time)
        // Started once held, so the cue's own tune does not cancel the job running it.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                sleep(wait)
                timer = null
                due = null
                cue()
            }
        timer = job
        job.start()
    }
}
