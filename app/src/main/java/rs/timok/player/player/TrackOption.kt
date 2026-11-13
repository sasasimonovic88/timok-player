package rs.timok.player.player

import androidx.media3.common.C
import androidx.media3.common.Tracks

/** A selectable audio or subtitle track of the current stream. */
data class TrackOption(
    val type: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    /** ISO language code from the stream, if any ("und" is dropped). */
    val language: String?,
    /** Label the stream gives the track, if any. */
    val label: String?,
    val codec: String?,
    val channelLayout: String?,
    val selected: Boolean,
) {
    companion object {
        fun from(tracks: Tracks): List<TrackOption> {
            val result = ArrayList<TrackOption>()
            tracks.groups.forEachIndexed { g, group ->
                if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val f = group.getTrackFormat(i)
                    val info = StreamInfo(audioChannels = if (f.channelCount > 0) f.channelCount else 0)
                    result += TrackOption(
                        type = group.type,
                        groupIndex = g,
                        trackIndex = i,
                        language = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED },
                        label = f.label?.takeIf { it.isNotBlank() },
                        codec = if (group.type == C.TRACK_TYPE_AUDIO) f.sampleMimeType?.let(StreamInfo::audioCodecName) else null,
                        channelLayout = if (group.type == C.TRACK_TYPE_AUDIO) info.channelLayout else null,
                        selected = group.isTrackSelected(i),
                    )
                }
            }
            return result
        }
    }
}

/** Picture format, mapped to PlayerView resize modes. */
enum class AspectMode { FIT, ZOOM, STRETCH }
