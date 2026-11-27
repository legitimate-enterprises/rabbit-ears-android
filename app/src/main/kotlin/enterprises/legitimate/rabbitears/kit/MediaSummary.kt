package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.HdrFormat
import enterprises.legitimate.rabbitears.api.models.MediaFile
import enterprises.legitimate.rabbitears.api.models.Track
import enterprises.legitimate.rabbitears.api.models.TrackKind

/** What the details screens say about one media file's tracks. */
class MediaSummary(val file: MediaFile) {
    val video: Track? = file.tracks.firstOrNull { it.kind == TrackKind.video }
    val audio: List<Track> = file.tracks.filter { it.kind == TrackKind.audio }
    val subtitles: List<Track> = file.tracks.filter { it.kind == TrackKind.subtitle }
    val defaultAudio: Track? = audio.firstOrNull { it.isDefault } ?: audio.firstOrNull()

    /** The `4K` and `HDR` tags beside the title. */
    val tags: List<String>
        get() {
            val video = video ?: return emptyList()
            val (w, h) = (video.width ?: return emptyList()) to (video.height ?: return emptyList())
            return listOfNotNull(
                "4K".takeIf { Format.resolution(w, h) == "4K" },
                "HDR".takeIf { video.hdrFormat != null },
            )
        }

    /** Such as `4K HDR10 · HEVC · 38 Mb/s`. */
    val videoLine: String?
        get() {
            val video = video ?: return null
            var quality = Format.resolution(video.width ?: 0, video.height ?: 0)
            video.hdrFormat?.let { quality += " " + HDR_NAMES.getValue(it) }
            return listOfNotNull(
                    quality,
                    video.codec.uppercase(),
                    file.bitrate?.let(Format::bitrate),
                )
                .joinToString(" · ")
        }

    /**
     * Every label for up to three tracks; past that the English labels and `+N other languages`, or
     * `N languages` when none is English.
     */
    val subtitleLine: String
        get() {
            val labels = subtitles.map(::lineLabel).distinct()
            if (subtitles.size <= 3) return labels.joinToString(", ")
            val (english, rest) = subtitles.partition { isEnglish(it.language) }
            val others = rest.map { Format.language(it.language) }.distinct().size
            if (english.isEmpty())
                return if (others == 1) labels.joinToString(", ") else "$others languages"
            val more =
                when (others) {
                    0 -> null
                    1 -> "+1 other language"
                    else -> "+$others other languages"
                }
            return (english.map(::lineLabel).distinct() + listOfNotNull(more)).joinToString(", ")
        }

    /** As [label], without the language twice when the title names it: `English SDH`. */
    private fun lineLabel(track: Track): String {
        val language = Format.language(track.language)
        val title = track.title?.takeIf { it.isNotBlank() }
        val label =
            when {
                title == null -> language
                title.contains(language, ignoreCase = true) -> title
                else -> "$language $title"
            }
        return if (track.isForced) "$label (forced)" else label
    }

    private fun isEnglish(code: String?) =
        code?.lowercase()?.split('-', '_')?.first() in setOf("en", "eng")

    val fileLine: String
        get() = "${file.path.substringAfterLast('/')} · ${Format.bytes(file.sizeBytes)}"

    /** `English · 5.1` for audio; `English SDH` or `English (forced)` for subtitles. */
    fun label(track: Track): String {
        val language = Format.language(track.language)
        if (track.kind == TrackKind.audio) {
            val channels =
                track.channels?.let { CHANNELS[it] ?: "$it ch" } ?: track.codec.uppercase()
            return "$language · $channels"
        }
        val label = track.title?.let { "$language $it" } ?: language
        return if (track.isForced) "$label (forced)" else label
    }

    companion object {
        val HDR_NAMES =
            mapOf(
                HdrFormat.hdr10 to "HDR10",
                HdrFormat.hdr10plus to "HDR10+",
                HdrFormat.dolby_vision to "Dolby Vision",
                HdrFormat.hlg to "HLG",
            )
        val CHANNELS = mapOf(1 to "Mono", 2 to "Stereo", 6 to "5.1", 8 to "7.1")
    }
}
