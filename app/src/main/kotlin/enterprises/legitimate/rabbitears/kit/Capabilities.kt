@file:SuppressLint("InlinedApi") // Platform constants are only compared here, never passed back.

package enterprises.legitimate.rabbitears.kit

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.MediaCodecInfo.CodecProfileLevel as P
import android.view.Display
import enterprises.legitimate.rabbitears.api.models.AudioRouteCapabilities
import enterprises.legitimate.rabbitears.api.models.CapabilitySnapshot
import enterprises.legitimate.rabbitears.api.models.DecoderCapabilities
import enterprises.legitimate.rabbitears.api.models.DisplayCapabilities
import enterprises.legitimate.rabbitears.api.models.HdrFormat
import enterprises.legitimate.rabbitears.api.models.NetworkPathCapabilities
import enterprises.legitimate.rabbitears.api.models.StreamProtocol
import enterprises.legitimate.rabbitears.api.models.VideoDecoder

/** One decoder from MediaCodecList for one MIME type, as the platform reports it. */
data class CodecFacts(
    val mime: String,
    val hardware: Boolean,
    /** MediaCodecInfo profile and level constants. */
    val profileLevels: List<Pair<Int, Int>> = emptyList(),
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFrameRate: Double = 0.0,
)

/** The current screen: physical pixels with the long side as the width, since video is wide. */
data class ScreenFacts(
    val width: Int,
    val height: Int,
    val refreshRate: Double,
    /** Display.HdrCapabilities HDR_TYPE constants. */
    val hdrTypes: List<Int> = emptyList(),
)

/** One output device from AudioManager. */
data class OutputDevice(
    val type: Int,
    val channelCounts: List<Int> = emptyList(),
    /** AudioFormat ENCODING constants. */
    val encodings: List<Int> = emptyList(),
    val sampleRates: List<Int> = emptyList(),
)

data class PathFacts(
    val metered: Boolean = false,
    /** Data Saver is on for this app and the network is metered, the only kind it restricts. */
    val lowData: Boolean = false,
    val vpn: Boolean = false,
    val cellular: Boolean = false,
) {
    companion object {
        fun of(
            unmetered: Boolean,
            dataSaver: Boolean,
            vpn: Boolean = false,
            cellular: Boolean = false,
        ) = PathFacts(!unmetered, !unmetered && dataSaver, vpn, cellular)
    }
}

/** The raw OS facts a snapshot is built from, kept apart so the mapping is testable off-device. */
data class DeviceFacts(
    val codecs: List<CodecFacts>,
    val screen: ScreenFacts?,
    val outputs: List<OutputDevice>,
    val path: PathFacts,
)

/** The capability group a change on this device invalidates. */
enum class CapabilityGroup {
    Display,
    AudioRoute,
    Network,
}

/** Where the player gets the current facts and hears when they change, on the main thread. */
interface CapabilitySource {
    fun facts(): DeviceFacts

    var onChange: ((CapabilityGroup) -> Unit)?
}

/** Maps OS facts to the server's capability groups. */
object Snapshot {
    private class Profile(val name: String, val depth: Int = 8, val hdr: HdrFormat? = null)

    private class Codec(
        val name: String,
        val profiles: Map<Int, Profile>,
        /** MediaCodecInfo level to ffprobe's level number; empty when ffprobe reports none. */
        val levels: Map<Int, Int> = emptyMap(),
    )

    private val hevcLevels =
        listOf(
                P.HEVCMainTierLevel1 to 30,
                P.HEVCMainTierLevel2 to 60,
                P.HEVCMainTierLevel21 to 63,
                P.HEVCMainTierLevel3 to 90,
                P.HEVCMainTierLevel31 to 93,
                P.HEVCMainTierLevel4 to 120,
                P.HEVCMainTierLevel41 to 123,
                P.HEVCMainTierLevel5 to 150,
                P.HEVCMainTierLevel51 to 153,
                P.HEVCMainTierLevel52 to 156,
                P.HEVCMainTierLevel6 to 180,
                P.HEVCMainTierLevel61 to 183,
                P.HEVCMainTierLevel62 to 186,
            )
            // Each high tier constant is its main tier's doubled.
            .flatMap { (main, idc) -> listOf(main to idc, main * 2 to idc) }
            .toMap()

    private val codecs =
        mapOf(
            "video/avc" to
                Codec(
                    "h264",
                    mapOf(
                        P.AVCProfileConstrainedBaseline to Profile("Constrained Baseline"),
                        P.AVCProfileBaseline to Profile("Baseline"),
                        P.AVCProfileMain to Profile("Main"),
                        P.AVCProfileHigh to Profile("High"),
                        P.AVCProfileHigh10 to Profile("High 10", 10),
                    ),
                    mapOf(
                        P.AVCLevel1 to 10,
                        P.AVCLevel11 to 11,
                        P.AVCLevel12 to 12,
                        P.AVCLevel13 to 13,
                        P.AVCLevel2 to 20,
                        P.AVCLevel21 to 21,
                        P.AVCLevel22 to 22,
                        P.AVCLevel3 to 30,
                        P.AVCLevel31 to 31,
                        P.AVCLevel32 to 32,
                        P.AVCLevel4 to 40,
                        P.AVCLevel41 to 41,
                        P.AVCLevel42 to 42,
                        P.AVCLevel5 to 50,
                        P.AVCLevel51 to 51,
                        P.AVCLevel52 to 52,
                        P.AVCLevel6 to 60,
                        P.AVCLevel61 to 61,
                        P.AVCLevel62 to 62,
                    ),
                ),
            "video/hevc" to
                Codec(
                    "hevc",
                    mapOf(
                        P.HEVCProfileMain to Profile("Main"),
                        P.HEVCProfileMain10 to Profile("Main 10", 10),
                        P.HEVCProfileMain10HDR10 to Profile("Main 10", 10, HdrFormat.hdr10),
                        P.HEVCProfileMain10HDR10Plus to Profile("Main 10", 10, HdrFormat.hdr10plus),
                    ),
                    hevcLevels,
                ),
            "video/x-vnd.on2.vp9" to
                Codec(
                    "vp9",
                    mapOf(
                        P.VP9Profile0 to Profile("Profile 0"),
                        P.VP9Profile2 to Profile("Profile 2", 10),
                        P.VP9Profile2HDR to Profile("Profile 2", 10, HdrFormat.hdr10),
                        P.VP9Profile2HDR10Plus to Profile("Profile 2", 10, HdrFormat.hdr10plus),
                    ),
                ),
            "video/av01" to
                Codec(
                    "av1",
                    mapOf(
                        P.AV1ProfileMain8 to Profile("Main"),
                        P.AV1ProfileMain10 to Profile("Main", 10),
                        P.AV1ProfileMain10HDR10 to Profile("Main", 10, HdrFormat.hdr10),
                        P.AV1ProfileMain10HDR10Plus to Profile("Main", 10, HdrFormat.hdr10plus),
                    ),
                ),
        )

    private const val DOLBY_VISION = "video/dolby-vision"

    private val audioNames =
        mapOf(
            "audio/mp4a-latm" to "aac",
            "audio/ac3" to "ac3",
            "audio/eac3" to "eac3",
            "audio/eac3-joc" to "eac3",
            "audio/flac" to "flac",
            "audio/opus" to "opus",
            "audio/mpeg" to "mp3",
            "audio/vorbis" to "vorbis",
            "audio/alac" to "alac",
            "audio/true-hd" to "truehd",
            "audio/vnd.dts" to "dts",
        )

    private val passthroughNames =
        mapOf(
            AudioFormat.ENCODING_AC3 to "ac3",
            AudioFormat.ENCODING_E_AC3 to "eac3",
            AudioFormat.ENCODING_E_AC3_JOC to "eac3",
            AudioFormat.ENCODING_DTS to "dts",
            AudioFormat.ENCODING_DTS_HD to "dts",
            AudioFormat.ENCODING_DOLBY_TRUEHD to "truehd",
        )

    private val hdrNames =
        mapOf(
            Display.HdrCapabilities.HDR_TYPE_HDR10 to HdrFormat.hdr10,
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS to HdrFormat.hdr10plus,
            Display.HdrCapabilities.HDR_TYPE_HLG to HdrFormat.hlg,
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION to HdrFormat.dolby_vision,
        )

    /** Output types in the order Android prefers them for media, most preferred first. */
    private val routePriority =
        listOf(
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )

    fun snapshot(
        facts: DeviceFacts,
        server: ServerEndpoint,
        quality: QualityChoice,
    ): CapabilitySnapshot =
        CapabilitySnapshot(
            decoder(facts.codecs),
            display(facts.screen),
            audioRoute(facts.outputs),
            network(facts.path, server, quality),
        )

    /**
     * Group A from the hardware decoders only, since the server copies video only for those. HLG
     * needs nothing beyond a 10-bit decode; Dolby Vision rides on HEVC when a Dolby Vision decoder
     * exists.
     */
    fun decoder(all: List<CodecFacts>): DecoderCapabilities {
        val dolbyVision = all.any { it.mime == DOLBY_VISION && it.hardware }
        val video = codecs.mapNotNull { (mime, codec) ->
            val found = all.filter { it.mime == mime && it.hardware }
            val pairs = found.flatMap { it.profileLevels }
            val profiles = pairs.mapNotNull { codec.profiles[it.first] }
            if (profiles.isEmpty()) return@mapNotNull null
            val depth = profiles.maxOf { it.depth }
            val hdr = buildList {
                profiles.mapNotNullTo(this) { it.hdr }
                if (depth >= 10) add(HdrFormat.hlg)
                if (codec.name == "hevc" && dolbyVision && depth >= 10) add(HdrFormat.dolby_vision)
            }
            VideoDecoder(
                codec = codec.name,
                hardware = true,
                maxWidth = found.maxOf { it.maxWidth },
                maxHeight = found.maxOf { it.maxHeight },
                maxFrameRate = found.maxOf { it.maxFrameRate },
                maxBitDepth = depth,
                profiles = profiles.map { it.name }.distinct(),
                maxLevel = pairs.mapNotNull { codec.levels[it.second] }.maxOrNull(),
                hdrFormats = hdr.distinct(),
            )
        }
        return DecoderCapabilities(
            protocols = listOf(StreamProtocol.http, StreamProtocol.hls),
            // ExoPlayer's own extractors open these as plain files, named as the server's prober
            // names them. A song plays as it is only when its container is listed.
            containers = listOf("mp4", "mov", "mkv", "webm", "mp3", "flac", "ogg", "wav"),
            videoCodecs = video,
            audioCodecs = all.mapNotNull { audioNames[it.mime] }.distinct(),
            // The player draws text sidecars itself; bitmap formats come converted.
            subtitleFormats = listOf("webvtt", "srt"),
        )
    }

    /** Without a screen the answer is a 1080p SDR display. */
    fun display(screen: ScreenFacts?): DisplayCapabilities {
        if (screen == null || screen.width <= 0 || screen.height <= 0)
            return DisplayCapabilities(1920, 1080, 60.0, emptyList())
        return DisplayCapabilities(
            maxOf(screen.width, screen.height),
            minOf(screen.width, screen.height),
            if (screen.refreshRate > 0) screen.refreshRate else 60.0,
            screen.hdrTypes.mapNotNull { hdrNames[it] }.distinct(),
        )
    }

    /**
     * The route is the most preferred output connected; with none of those it is the built-in
     * speaker, stereo and decoded. An empty channel list means the device takes any count.
     */
    fun audioRoute(outputs: List<OutputDevice>): AudioRouteCapabilities {
        val route =
            routePriority.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
                ?: return AudioRouteCapabilities(2, emptyList())
        val channels = route.channelCounts.maxOrNull() ?: 2
        return AudioRouteCapabilities(
            channels = channels.coerceIn(1, 16),
            passthroughCodecs = route.encodings.mapNotNull { passthroughNames[it] }.distinct(),
            maxSampleRate = route.sampleRates.maxOrNull()?.takeIf { it >= 8000 },
        )
    }

    /**
     * auto is always sent, since the server treats a snapshot without it as a v1 client; the cap
     * only for a fixed rung. A server at a public address is discovered locally once a local check
     * finds it.
     */
    fun network(
        path: PathFacts,
        server: ServerEndpoint,
        quality: QualityChoice,
    ): NetworkPathCapabilities =
        NetworkPathCapabilities(
            discoveredLocally = server.atHome,
            metered = path.metered,
            lowData = path.lowData,
            vpn = path.vpn,
            auto = quality == QualityChoice.Auto,
            maxBitrate = (quality as? QualityChoice.Rung)?.bitrate,
        )
}

/**
 * What the viewer asked for. Auto is the default until they pick another; a rung is named by its
 * bitrate, which is also the cap sent to the server.
 */
sealed interface QualityChoice {
    data object Auto : QualityChoice

    data object Original : QualityChoice

    data class Rung(val bitrate: Long) : QualityChoice
}
