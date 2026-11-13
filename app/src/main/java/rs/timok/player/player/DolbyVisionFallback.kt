package rs.timok.player.player

import android.content.Context
import android.media.MediaCrypto
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Renderers factory whose video renderer plays Dolby Vision on devices without a Dolby Vision decoder.
 *
 * Media3 falls back to HEVC only for profile 8. Profiles 4 and 7 (UHD Blu-ray and their remuxes) also carry an
 * HEVC 10-bit base layer; a plain HEVC decoder plays it and skips the enhancement-layer NAL units, which is what
 * Kodi and other players do. The picture is the HDR10 base layer, without the Dolby Vision dynamic metadata.
 * Profile 5 is left alone: its base layer uses the IPT colour space and would show wrong (purple/green) colours.
 */
@OptIn(UnstableApi::class)
class FallbackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        // Swap the platform video renderer for ours, built the same way; extension renderers stay as they are.
        val index = out.indexOfFirst { it.javaClass == MediaCodecVideoRenderer::class.java }
        if (index < 0) return
        out[index] = DolbyVisionFallbackRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        )
    }
}

@OptIn(UnstableApi::class)
private class DolbyVisionFallbackRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, hevcBaseLayer(format) ?: format)

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> = super.getDecoderInfos(mediaCodecSelector, hevcBaseLayer(format) ?: format, requiresSecureDecoder)

    // Configure the HEVC decoder with an HEVC format, so no Dolby Vision profile is passed to it.
    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo,
        format: Format,
        crypto: MediaCrypto?,
        codecOperatingRate: Float,
    ): MediaCodecAdapter.Configuration =
        super.getMediaCodecConfiguration(codecInfo, hevcBaseLayer(format) ?: format, crypto, codecOperatingRate)

    /** The same stream described as HEVC, when it is Dolby Vision this device can only play through its base layer. */
    private fun hevcBaseLayer(format: Format): Format? {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return null
        val codecs = format.codecs ?: return null
        if (!codecs.startsWith("dvhe.") && !codecs.startsWith("dvh1.")) return null
        val profile = codecs.substringAfter('.').substringBefore('.').toIntOrNull() ?: return null
        if (profile !in HEVC_BASE_PROFILES || hasDolbyVisionDecoder) return null
        if (!logged) {
            logged = true
            Log.i(TAG, "No Dolby Vision decoder: playing $codecs through its HEVC base layer")
        }
        return format.buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs(null)
            .build()
    }

    private var logged = false

    private val hasDolbyVisionDecoder: Boolean by lazy {
        runCatching { MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isNotEmpty() }
            .getOrDefault(false)
    }

    private companion object {
        const val TAG = "DolbyVisionFallback"
        /** Profiles with an HDR10/SDR-compatible HEVC base layer (5 is IPT and not compatible). */
        val HEVC_BASE_PROFILES = setOf(4, 7, 8)
    }
}
