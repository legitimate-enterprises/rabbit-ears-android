package enterprises.legitimate.rabbitears.kit

import enterprises.legitimate.rabbitears.api.models.Negotiated
import enterprises.legitimate.rabbitears.api.models.PlanEntry
import enterprises.legitimate.rabbitears.api.models.PlaybackStream
import enterprises.legitimate.rabbitears.api.models.Sidecar
import enterprises.legitimate.rabbitears.api.models.Verdict
import java.io.File
import java.net.URI

/** A title on the device, which plays with no session and no server. */
data class LocalCopy(
    val file: File,
    val subtitles: List<Pair<StoredSubtitle, File>>,
    val runtimeMs: Long?,
    val editionId: String?,
)

object LocalPlayback {
    /** The session id a local copy plays under; the server never hears of it. */
    const val SESSION = "local"

    /** A title counts as watched once this share of it has played, as the server counts it. */
    const val WATCHED_SHARE = 0.9

    fun isLocal(stream: PlaybackStream?) = stream?.sessionId == SESSION

    /** The local copy as the player takes a stream: the file itself, its sidecars beside it. */
    fun stream(copy: LocalCopy, positionMs: Long) =
        PlaybackStream(
            sessionId = SESSION,
            mediaFileId = "",
            entry =
                PlanEntry(
                    0,
                    Verdict.direct_play,
                    PlanEntry.VideoAction.copy,
                    PlanEntry.AudioAction.copy,
                    PlanEntry.SubtitleAction.sidecar,
                    bad = false,
                ),
            streamUrl = copy.file.toURI().toString(),
            streamKind = PlaybackStream.StreamKind.file,
            positionMs = positionMs,
            negotiated = Negotiated(Verdict.direct_play, 0, 0, 0),
            rungs = emptyList(),
            sidecars =
                copy.subtitles.map { (s, file) ->
                    Sidecar(
                        s.trackId,
                        Sidecar.Format.webvtt,
                        s.forced,
                        file.toURI().toString(),
                        s.language,
                        s.title,
                    )
                },
        )

    fun watched(positionMs: Long, runtimeMs: Long?) =
        runtimeMs != null && runtimeMs > 0 && positionMs >= runtimeMs * WATCHED_SHARE

    /** A sidecar's text: read from the device for a local copy, else fetched. */
    fun text(api: Api, url: String): String? =
        if (url.startsWith("file:")) runCatching { File(URI(url)).readText() }.getOrNull()
        else api.bytes(url).body?.decodeToString()
}

fun DownloadStore.local(profileId: String, itemId: String): LocalCopy? {
    val title = onDevice(profileId, itemId) ?: return null
    val file = media(title.file ?: return null).takeIf { it.exists() } ?: return null
    return LocalCopy(
        file,
        title.subtitles.map { it to media(it.file) }.filter { it.second.exists() },
        title.runtimeMs,
        title.editionId,
    )
}
