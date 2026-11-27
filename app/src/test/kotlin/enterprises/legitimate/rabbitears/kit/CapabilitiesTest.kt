package enterprises.legitimate.rabbitears.kit

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.MediaCodecInfo.CodecProfileLevel as P
import android.view.Display.HdrCapabilities
import enterprises.legitimate.rabbitears.api.models.HdrFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesTest {
    private val avc =
        CodecFacts(
            "video/avc",
            hardware = true,
            profileLevels =
                listOf(
                    P.AVCProfileHigh to P.AVCLevel52,
                    P.AVCProfileMain to P.AVCLevel41,
                    P.AVCProfileConstrainedBaseline to P.AVCLevel31,
                ),
            maxWidth = 4096,
            maxHeight = 2176,
            maxFrameRate = 60.0,
        )
    private val hevc =
        CodecFacts(
            "video/hevc",
            hardware = true,
            profileLevels =
                listOf(
                    P.HEVCProfileMain to P.HEVCMainTierLevel51,
                    P.HEVCProfileMain10 to P.HEVCHighTierLevel51,
                    P.HEVCProfileMain10HDR10 to P.HEVCMainTierLevel51,
                ),
            maxWidth = 7680,
            maxHeight = 4320,
            maxFrameRate = 60.0,
        )

    @Test
    fun mapsHardwareDecodersToFfprobeNames() {
        val decoder = Snapshot.decoder(listOf(avc, hevc))
        val (h264, h265) = decoder.videoCodecs
        assertEquals("h264", h264.codec)
        assertEquals(listOf("High", "Main", "Constrained Baseline"), h264.profiles)
        assertEquals(52, h264.maxLevel)
        assertEquals(8, h264.maxBitDepth)
        assertEquals(emptyList<HdrFormat>(), h264.hdrFormats)
        assertEquals("hevc", h265.codec)
        assertEquals(listOf("Main", "Main 10"), h265.profiles)
        // Level 5.1 is 153 to ffprobe, in either tier.
        assertEquals(153, h265.maxLevel)
        assertEquals(10, h265.maxBitDepth)
        assertEquals(listOf(HdrFormat.hdr10, HdrFormat.hlg), h265.hdrFormats)
        assertEquals(7680, h265.maxWidth)
        assertTrue(h265.hardware)
    }

    @Test
    fun leavesOutSoftwareDecodersAndUnknownProfiles() {
        val software = hevc.copy(hardware = false)
        val vp9 = CodecFacts("video/x-vnd.on2.vp9", true, listOf(P.VP9Profile1 to P.VP9Level5))
        assertEquals(emptyList<Any>(), Snapshot.decoder(listOf(software, vp9)).videoCodecs)
    }

    @Test
    fun dolbyVisionRidesOnHevc() {
        val dv = CodecFacts("video/dolby-vision", hardware = true)
        val decoder = Snapshot.decoder(listOf(hevc, dv))
        assertTrue(HdrFormat.dolby_vision in decoder.videoCodecs.single().hdrFormats!!)
    }

    @Test
    fun namesAudioDecodersOnce() {
        val audio =
            listOf(
                    "audio/mp4a-latm",
                    "audio/eac3",
                    "audio/eac3-joc",
                    "audio/flac",
                    "audio/x-unknown",
                )
                .map { CodecFacts(it, hardware = false) }
        assertEquals(listOf("aac", "eac3", "flac"), Snapshot.decoder(audio).audioCodecs)
    }

    @Test
    fun namesAudioFilesAsTheServersProberDoes() {
        // AIFF is left out: ExoPlayer has no extractor for it.
        assertEquals(
            listOf("mp4", "mov", "mkv", "webm", "mp3", "flac", "ogg", "wav"),
            Snapshot.decoder(emptyList()).containers,
        )
    }

    @Test
    fun displayIsLandscapeWithItsHdrTypes() {
        val screen =
            ScreenFacts(
                1080,
                2400,
                120.0,
                listOf(HdrCapabilities.HDR_TYPE_HDR10, HdrCapabilities.HDR_TYPE_HLG, 99),
            )
        val display = Snapshot.display(screen)
        assertEquals(2400, display.width)
        assertEquals(1080, display.height)
        assertEquals(120.0, display.refreshRate, 0.0)
        assertEquals(listOf(HdrFormat.hdr10, HdrFormat.hlg), display.hdrFormats)
        assertEquals(1920, Snapshot.display(null).width)
    }

    @Test
    fun routeIsThePreferredOutput() {
        val speaker = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, listOf(2))
        assertEquals(2, Snapshot.audioRoute(listOf(speaker)).channels)
        val hdmi =
            OutputDevice(
                AudioDeviceInfo.TYPE_HDMI,
                listOf(2, 6, 8),
                listOf(
                    AudioFormat.ENCODING_PCM_16BIT,
                    AudioFormat.ENCODING_AC3,
                    AudioFormat.ENCODING_E_AC3,
                ),
                listOf(48000, 192000),
            )
        val bluetooth = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, listOf(2))
        val route = Snapshot.audioRoute(listOf(speaker, bluetooth, hdmi))
        assertEquals(8, route.channels)
        assertEquals(listOf("ac3", "eac3"), route.passthroughCodecs)
        assertEquals(192000, route.maxSampleRate)
        val headphones = Snapshot.audioRoute(listOf(speaker, bluetooth))
        assertEquals(emptyList<String>(), headphones.passthroughCodecs)
        assertNull(headphones.maxSampleRate)
    }

    @Test
    fun dataSaverIsLowDataOnlyOnAMeteredNetwork() {
        assertFalse(PathFacts.of(unmetered = true, dataSaver = true).lowData)
        assertTrue(PathFacts.of(unmetered = false, dataSaver = true).lowData)
        assertFalse(PathFacts.of(unmetered = false, dataSaver = false).lowData)
        assertTrue(PathFacts.of(unmetered = false, dataSaver = false).metered)
    }

    @Test
    fun networkAlwaysSaysAutoAndCapsOnlyARung() {
        val home = ServerEndpoint.parse("192.168.1.20")!!
        val away = ServerEndpoint.parse("media.example.com")!!
        val path = PathFacts(metered = true, lowData = true, vpn = true)
        val auto = Snapshot.network(path, home, QualityChoice.Auto)
        assertTrue(auto.discoveredLocally)
        assertTrue(auto.metered && auto.lowData && auto.vpn)
        assertEquals(true, auto.auto)
        assertNull(auto.maxBitrate)
        val rung = Snapshot.network(PathFacts(), away, QualityChoice.Rung(4_000_000))
        assertFalse(rung.discoveredLocally)
        assertEquals(false, rung.auto)
        assertEquals(4_000_000L, rung.maxBitrate)
        assertNull(Snapshot.network(PathFacts(), away, QualityChoice.Original).maxBitrate)
    }
}
