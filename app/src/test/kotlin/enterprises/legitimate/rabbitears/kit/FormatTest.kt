package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.infrastructure.Serializer
import enterprises.legitimate.rabbitears.api.models.MediaFile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun readsLikeTheBoards() {
        assertEquals("2h 46m", Format.runtime(166))
        assertEquals("2h", Format.runtime(120))
        assertEquals("50m", Format.runtime(50))
        assertEquals("23m left", Format.remaining(23 * 60_000L))
        assertEquals("2h 46m", Format.minutes(166 * 60_000L + 29_999))
        assertEquals("50m", Format.minutes(49 * 60_000L + 30_000))
        assertEquals("1,140 movies", Format.plural(1140, "movie"))
        assertEquals("1 season", Format.plural(1, "season"))
        assertEquals("Jan 17, 2025", Format.date(LocalDate.of(2025, 1, 17)))
        assertEquals("4:12", Format.clock(252))
        assertEquals("1080p", Format.resolution(1920, 804))
        assertEquals("38 Mb/s", Format.bitrate(38_000_000))
        assertEquals("English", Format.language("en"))
        assertEquals("24.1 GB", Format.bytes(24_100_000_000))
    }

    @Test
    fun summarizesAFilesTracks() {
        val json =
            """{"id":1,"itemId":7,"path":"/media/Dune.Part.Two.2024.2160p.mkv","sizeBytes":24100000000,
            "modifiedAt":"${Json.T}","bitrate":38000000,"chapters":[],"tracks":[
            {"id":1,"streamIndex":0,"kind":"video","codec":"hevc","isDefault":true,"isForced":false,"width":3840,"height":2160,"hdrFormat":"hdr10"},
            {"id":2,"streamIndex":1,"kind":"audio","codec":"truehd","isDefault":false,"isForced":false,"language":"en","channels":8},
            {"id":3,"streamIndex":2,"kind":"audio","codec":"ac3","isDefault":true,"isForced":false,"language":"en","channels":6},
            {"id":4,"streamIndex":3,"kind":"subtitle","codec":"subrip","isDefault":false,"isForced":false,"language":"en"},
            {"id":5,"streamIndex":4,"kind":"subtitle","codec":"subrip","isDefault":false,"isForced":false,"language":"en","title":"SDH"},
            {"id":6,"streamIndex":5,"kind":"subtitle","codec":"subrip","isDefault":false,"isForced":true,"language":"es"}]}"""
        val media =
            MediaSummary(
                Serializer.kotlinxSerializationJson.decodeFromString(MediaFile.serializer(), json),
            )
        assertEquals(listOf("4K", "HDR"), media.tags)
        assertEquals("4K HDR10 · HEVC · 38 Mb/s", media.videoLine)
        assertEquals(3L, media.defaultAudio?.id)
        assertEquals("English · 7.1", media.label(media.audio.first()))
        assertEquals("English, English SDH, Spanish (forced)", media.subtitleLine)
        assertEquals("Dune.Part.Two.2024.2160p.mkv · 24.1 GB", media.fileLine)
    }

    /** Each track as `language[/title][/forced]`. */
    private fun subtitleLine(vararg tracks: String): String {
        val json = tracks.mapIndexed { i, spec ->
            val parts = spec.split("/")
            val title = parts.drop(1).firstOrNull { it != "forced" }
            """{"id":$i,"streamIndex":$i,"kind":"subtitle","codec":"subrip","isDefault":false,""" +
                """"isForced":${"forced" in parts},"language":"${parts[0]}"""" +
                (title?.let { ""","title":"$it"""" } ?: "") +
                "}"
        }
        val file =
            """{"id":1,"itemId":7,"path":"/m.mkv","sizeBytes":1,"modifiedAt":"${Json.T}",""" +
                """"chapters":[],"tracks":[${json.joinToString(",")}]}"""
        return MediaSummary(
                Serializer.kotlinxSerializationJson.decodeFromString(MediaFile.serializer(), file),
            )
            .subtitleLine
    }

    @Test
    fun theSubtitlesLineNamesEnglishAndCountsTheRest() {
        assertEquals("English", subtitleLine("eng"))
        assertEquals("English, English SDH, Spanish", subtitleLine("eng", "eng/SDH", "spa"))
        assertEquals(
            "English, English SDH, +3 other languages",
            subtitleLine("eng", "eng/SDH", "spa", "fre", "ger"),
        )
        assertEquals(
            "English, +1 other language",
            subtitleLine("eng", "spa", "spa/SDH", "spa/forced"),
        )
        assertEquals("English", subtitleLine("eng", "eng", "eng", "eng"))
        assertEquals("4 languages", subtitleLine("spa", "fre", "ger", "ita"))
        assertEquals("Spanish, Spanish SDH", subtitleLine("spa", "spa/SDH", "spa", "spa"))
        assertEquals("English SDH", subtitleLine("eng/English SDH"))
        assertEquals("English (forced)", subtitleLine("eng/forced"))
        assertEquals("English, +2 other languages", subtitleLine("en-US", "und", "spa", "spa"))
    }

    @Test
    fun pinEntryTakesFourDigits() {
        val pin = PinEntry()
        assertFalse(pin.type('1'))
        assertFalse(pin.type('x'))
        pin.deleteBackward()
        assertEquals("", pin.digits)
        "123".forEach { pin.type(it) }
        assertTrue(pin.type('4'))
        assertFalse(pin.type('5'))
        assertEquals("1234", pin.digits)
    }
}
