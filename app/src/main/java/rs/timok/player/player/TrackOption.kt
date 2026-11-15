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

/**
 * Picture format. FIT/ZOOM/STRETCH only differ when the video's shape differs from the screen's, so 16:9 video on
 * a 16:9 TV looks the same in all three; the forced 16:9 and 4:3 shapes always change the picture.
 */
enum class AspectMode {
    FIT, RATIO_16_9, RATIO_4_3, ZOOM, STRETCH;

    /** Shape the picture is forced into (inside the screen), or null to follow the video. */
    val forcedRatio: Float? get() = when (this) {
        RATIO_16_9 -> 16f / 9f
        RATIO_4_3 -> 4f / 3f
        else -> null
    }

    val label: Int get() = when (this) {
        FIT -> rs.timok.player.R.string.aspect_fit
        RATIO_16_9 -> rs.timok.player.R.string.aspect_16_9
        RATIO_4_3 -> rs.timok.player.R.string.aspect_4_3
        ZOOM -> rs.timok.player.R.string.aspect_zoom
        STRETCH -> rs.timok.player.R.string.aspect_stretch
    }
}
