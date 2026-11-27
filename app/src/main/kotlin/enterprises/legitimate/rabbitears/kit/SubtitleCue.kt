package enterprises.legitimate.rabbitears.kit

/** One timed subtitle from a WebVTT or SRT sidecar, drawn by the player. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String) {
    companion object {
        private val tag = Regex("<[^>]+>")

        /** Reads WebVTT or SRT; markup tags and cue settings are dropped. */
        fun parse(text: String): List<SubtitleCue> =
            text.replace("\r\n", "\n").split("\n\n").mapNotNull { block ->
                val lines = block.split("\n")
                val timing = lines.indexOfFirst { "-->" in it }
                if (timing < 0) return@mapNotNull null
                val times = lines[timing].split("-->")
                val start = millis(times[0]) ?: return@mapNotNull null
                val end = millis(times[1].trim().substringBefore(' ')) ?: return@mapNotNull null
                val body = lines.drop(timing + 1).joinToString("\n").replace(tag, "").trim()
                if (body.isEmpty()) null else SubtitleCue(start, end, body)
            }

        /** `hh:mm:ss.mmm` or `mm:ss.mmm`, with a comma or a dot before the milliseconds. */
        fun millis(text: String): Long? {
            val parts = text.trim().replace(',', '.').split(':').map { it.toDoubleOrNull() }
            if (parts.size !in 2..3 || parts.any { it == null }) return null
            return Math.round(parts.fold(0.0) { total, part -> total * 60 + part!! } * 1000)
        }
    }
}

fun List<SubtitleCue>.at(ms: Long): String? = firstOrNull {
    it.startMs <= ms && ms < it.endMs
}
    ?.text
