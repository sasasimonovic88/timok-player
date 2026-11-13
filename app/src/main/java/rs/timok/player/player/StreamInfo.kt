package rs.timok.player.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

/** Technical details of the stream that is playing, shown as badges on the info bar. */
data class StreamInfo(
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    /** True when [frameRate] comes from the stream itself rather than from counting rendered frames. */
    val frameRateFromStream: Boolean = false,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    val audioTrackCount: Int = 0,
    val hasSubtitles: Boolean = false,
    /** Measured network data rate, smoothed over a few seconds; 0 when unknown. */
    val bitrateKbps: Int = 0,
) {
    val resolutionLabel: String?
        get() = when {
            height <= 0 -> null
            height >= 2000 -> "4K"
            height >= 1000 -> "FHD"
            height >= 700 -> "HD"
            else -> "SD"
        }

    val channelLayout: String?
        get() = when (audioChannels) {
            0 -> null
            1 -> "1.0"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${audioChannels}ch"
        }

    companion object {
        fun fromTracks(tracks: Tracks): StreamInfo {
            var video: Format? = null
            var audio: Format? = null
            var audioTracks = 0
            var subtitles = false
            for (group in tracks.groups) {
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> if (video == null) video = selectedOrFirst(group)
                    C.TRACK_TYPE_AUDIO -> {
                        audioTracks += group.length
                        if (group.isSelected || audio == null) audio = selectedOrFirst(group)
                    }
                    C.TRACK_TYPE_TEXT -> subtitles = true
                }
            }
            return StreamInfo(
                width = video?.width?.takeIf { it > 0 } ?: 0,
                height = video?.height?.takeIf { it > 0 } ?: 0,
                frameRate = video?.frameRate?.takeIf { it > 0 } ?: 0f,
                frameRateFromStream = (video?.frameRate ?: 0f) > 0f,
                videoCodec = video?.sampleMimeType?.let(::videoCodecName),
                audioCodec = audio?.sampleMimeType?.let(::audioCodecName),
                audioChannels = audio?.channelCount?.takeIf { it > 0 } ?: 0,
                audioTrackCount = audioTracks,
                hasSubtitles = subtitles,
            )
        }

        private fun selectedOrFirst(group: Tracks.Group): Format {
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i)
            return group.getTrackFormat(0)
        }

        private fun videoCodecName(mime: String) = when (mime) {
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            else -> null
        }

        fun audioCodecName(mime: String) = when (mime) {
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_DTS -> "DTS"
            else -> null
        }
    }
}
