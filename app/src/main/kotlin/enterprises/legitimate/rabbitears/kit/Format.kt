package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Item
import enterprises.legitimate.rabbitears.api.models.Verdict
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The short strings the boards use for times, counts and dates. */
object Format {
    fun runtime(minutes: Int): String {
        val (hours, rest) = minutes / 60 to minutes % 60
        if (hours == 0) return "${rest}m"
        return if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
    }

    /** A length in milliseconds as a runtime, to the nearest minute: `42m`. */
    fun minutes(ms: Long): String = runtime(((ms + 30_000) / 60_000).toInt())

    fun remaining(ms: Long): String = "${minutes(ms)} left"

    fun clock(seconds: Long): String {
        val s = maxOf(0, seconds)
        val (h, m, ss) = Triple(s / 3600, s / 60 % 60, s % 60)
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    fun plural(n: Int, one: String, many: String = one + "s"): String =
        "%,d %s".format(Locale.US, n, if (n == 1) one else many)

    fun episodeCode(item: Item): String = "S${item.seasonNumber ?: 0} E${item.episodeNumber ?: 0}"

    /** `Jan 17, 2025`. */
    fun date(day: LocalDate): String =
        day.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))

    /**
     * `4K` or `<rows>p` named by the nearest standard row count, so a 2.39:1 1080p file reads
     * 1080p.
     */
    fun resolution(width: Int, height: Int): String {
        val rows = maxOf(height, width * 9 / 16)
        if (rows >= 2000) return "4K"
        return "${listOf(1440, 1080, 720, 576, 480, 360, 240).firstOrNull { rows >= it * 9 / 10 } ?: rows}p"
    }

    fun bitrate(bitsPerSecond: Long): String {
        if (bitsPerSecond < 1_000_000) return "${bitsPerSecond / 1000} kb/s"
        val mbps = bitsPerSecond / 1_000_000.0
        return if (mbps >= 10 || mbps == Math.rint(mbps)) "${Math.round(mbps)} Mb/s"
        else "%.1f Mb/s".format(Locale.US, mbps)
    }

    fun language(code: String?): String {
        if (code.isNullOrEmpty() || code == "und") return "Unknown"
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return if (name.isEmpty() || name.equals(code, true)) code.uppercase() else name
    }

    fun bytes(n: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = n.toDouble()
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return if (unit == 0) "$n B" else "%.1f %s".format(Locale.US, value, units[unit])
    }

    fun verdict(verdict: Verdict): String =
        when (verdict) {
            Verdict.direct_play -> "Direct play"
            Verdict.remux -> "Remux"
            Verdict.audio_transcode -> "Audio transcode"
            Verdict.video_transcode -> "Transcode"
        }

    /** The quality menu's second line: what caps the stream, or where it plays. */
    fun qualityHeading(away: Boolean, path: PathFacts): String =
        when {
            path.lowData -> "Data Saver, up to 1.5 Mb/s"
            path.metered && path.cellular -> "On cellular, up to 4 Mb/s"
            path.metered -> "On a metered network, up to 4 Mb/s"
            away -> "Away from home"
            else -> "On your home network"
        }
}
