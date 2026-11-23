package rs.timok.player.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/** Which video decoders the player tries first. */
enum class DecoderMode {
    /** The box's own (hardware) decoders first, the platform default. */
    HARDWARE,
    /**
     * Software decoders first. Helps on boxes whose hardware decoder shows artefacts or a black picture for
     * some streams, but needs a strong CPU: HD and 4K may stutter.
     */
    SOFTWARE,
}

/** How far ahead the player loads. The byte cap (a quarter of the heap) applies in every mode. */
enum class BufferMode(val maxMs: Long, val startMs: Long, val restartMs: Long) {
    /** Media3 defaults: up to 50 s, start after 2.5 s, 5 s after a stall. */
    AUTO(50_000, 2_500, 5_000),
    /** Faster start and channel change, less reserve on a shaky connection. */
    SHORT(15_000, 1_000, 2_000),
    /** More reserve for slow or unstable internet (mostly helps movies); slower to recover after a stall. */
    LONG(120_000, 2_500, 10_000),
}

/** Orders video decoders by [mode]; asked each time a decoder is created, so a change applies to the next channel. */
@UnstableApi
class PreferenceCodecSelector(private val mode: () -> DecoderMode) : MediaCodecSelector {
    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean) =
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder).let { list ->
            if (mode() == DecoderMode.SOFTWARE && MimeTypes.isVideo(mimeType)) {
                // Stable sort: software ones first, in their original order; hardware stays as a fallback.
                list.sortedBy { !it.softwareOnly }
            } else {
                list
            }
        }
}

/**
 * [DefaultLoadControl] with time limits that follow [mode] while playing. The base control keeps the allocator
 * and the byte cap; it is built with the largest limit, and this wrapper stops loading earlier when the mode asks.
 */
@UnstableApi
class AdjustableLoadControl(
    private val base: DefaultLoadControl,
    /** Byte cap the base control was built with. */
    private val targetBytes: Int,
    private val mode: () -> BufferMode,
) : LoadControl {
    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        base.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)
    override fun getAllocator(): Allocator = base.allocator
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (parameters.bufferedDurationUs >= mode().maxMs * 1000) return false
        return base.shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val m = mode()
        if (m == BufferMode.AUTO) return base.shouldStartPlayback(parameters)
        var needUs = (if (parameters.rebuffering) m.restartMs else m.startMs) * 1000
        // Live: never wait for more than half the distance to the live edge (as the base control does).
        if (parameters.targetLiveOffsetUs != androidx.media3.common.C.TIME_UNSET) {
            needUs = minOf(needUs, parameters.targetLiveOffsetUs / 2)
        }
        // A full byte budget also counts as enough: loading has stopped, waiting longer would wait forever.
        val bytesFull = base.allocator.totalBytesAllocated >= targetBytes
        return parameters.bufferedDurationUs >= needUs || bytesFull ||
            (m == BufferMode.SHORT && base.shouldStartPlayback(parameters))
    }
}
