package rs.timok.player.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.util.EventLogger
import rs.timok.player.BuildConfig
import rs.timok.player.data.log.ErrorLog
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data object Buffering : PlaybackStatus
    data object Playing : PlaybackStatus
    data class Reconnecting(val attempt: Int) : PlaybackStatus
    data class Failed(val reason: String) : PlaybackStatus
    /** VOD reached its end (live streams never report this; they reconnect). */
    data object Ended : PlaybackStatus
}

/**
 * Single ExoPlayer for live TV. Only one stream is ever open (max_connections):
 * the previous one is stopped before the next is prepared. Reconnects on its own
 * with backoff when the stream drops.
 */
@OptIn(UnstableApi::class)
class LivePlayer(private val context: Context, private val okHttp: OkHttpClient, userAgent: String) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Network bytes when the stall watchdog was last (re)armed, and when the current item started. */
    private var stallBytes = 0L
    private var startedAt = 0L

    /** Bytes received from the network, counted on loader threads. */
    private val bytesLoaded = AtomicLong()
    private val transferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            // Where each request starts: a stalled VOD start shows whether the player is waiting on a seek.
            if (isNetwork && !live) Log.i(TAG, "Request from byte ${dataSpec.position} (length ${dataSpec.length})")
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (isNetwork && !live) Log.i(TAG, "Request from byte ${dataSpec.position} ended, total ${bytesLoaded.get()} bytes")
        }
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytesLoaded.addAndGet(bytesTransferred.toLong())
        }
    }

    private val trackSelector = DefaultTrackSelector(context)

    /** When true, video above the display resolution (never below 1080p) is not decoded at all. */
    private var limitToDisplay = true

    val player: ExoPlayer = run {
        val dataSource = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(userAgent)
            .setTransferListener(transferListener)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        // Platform decoders first; the bundled FFmpeg renderer covers audio the box cannot decode (e.g. MP2, AC3).
        // The factory also plays Dolby Vision through its HEVC base layer on boxes without a Dolby Vision decoder.
        val renderers = FallbackRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // If a hardware decoder fails to initialise, try the next one instead of failing playback.
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl())
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .build()
            .apply {
                playWhenReady = true
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }

    private val _status = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val status: StateFlow<PlaybackStatus> = _status

    private val _streamInfo = MutableStateFlow(StreamInfo())
    val streamInfo: StateFlow<StreamInfo> = _streamInfo

    private val _audioOnly = MutableStateFlow(false)
    /** Live stream with sound but no picture (a radio channel); the UI shows a radio screen instead of black. */
    val audioOnly: StateFlow<Boolean> = _audioOnly

    private val _tracks = MutableStateFlow<List<TrackOption>>(emptyList())
    /** Audio and subtitle tracks of the current stream. */
    val tracks: StateFlow<List<TrackOption>> = _tracks

    /**
     * Stream bitrate from HLS segments (bytes / media duration), smoothed over a few segments.
     * Null for progressive TS, which has no segments; then the network data rate is used instead.
     */
    private var segmentKbps: Int? = null

    private val _slowDecoding = MutableStateFlow(false)
    /**
     * True once the decoder of the current item keeps falling behind (many late frames dropped), e.g. 4K HEVC
     * 10-bit on a weak box. Raised at most once per item; the UI suggests an HD version.
     */
    val slowDecoding: StateFlow<Boolean> = _slowDecoding

    // Once per second: network data rate (fallback bitrate) and rendered frames (fps).
    private val rateSamples = ArrayDeque<Long>()
    private val frameSamples = ArrayDeque<Int>()
    private var lastBytes = 0L
    private var lastFrames = -1
    /** Per-second (rendered, dropped) frame counts while playing, for [slowDecoding]. */
    private val dropSamples = ArrayDeque<Pair<Int, Int>>()
    private var lastRenderedForDrops = -1
    /** When uninterrupted playback started (0 while paused or buffering), for the drop check warm-up. */
    private var playingSince = 0L
    private var lastDropped = -1
    private val rateTicker = object : Runnable {
        override fun run() {
            val total = bytesLoaded.get()
            rateSamples.addLast(total - lastBytes)
            lastBytes = total
            while (rateSamples.size > RATE_WINDOW_S) rateSamples.removeFirst()
            var info = _streamInfo.value
            if (segmentKbps == null && rateSamples.size >= 3) {
                val kbps = (rateSamples.sum() * 8 / 1000 / rateSamples.size).toInt()
                // Keep the last value through short stalls instead of flashing 0.
                if (kbps > 0) info = info.copy(bitrateKbps = kbps)
            }

            val counters = player.videoDecoderCounters
            if (counters != null) {
                counters.ensureUpdated()
                val frames = counters.renderedOutputBufferCount
                if (lastFrames >= 0 && frames >= lastFrames) frameSamples.addLast(frames - lastFrames)
                lastFrames = frames
                while (frameSamples.size > FPS_WINDOW_S) frameSamples.removeFirst()
                if (frameSamples.size >= FPS_WINDOW_S && info.frameRateFromStream.not()) {
                    val fps = frameSamples.sum().toFloat() / frameSamples.size
                    if (fps >= 10f) info = info.copy(frameRate = snapFps(fps))
                }
                checkDroppedFrames(frames, counters.droppedBufferCount)
            }
            if (info != _streamInfo.value) _streamInfo.value = info
            mainHandler.postDelayed(this, 1000)
        }
    }

    /**
     * Raises [slowDecoding] when, over the last [DROP_WINDOW_S] seconds of actual playback, at least
     * [DROP_RATIO] of the frames were dropped for being late and fewer than [MAX_CHOPPY_FPS] were shown. Paused or buffering seconds are not counted,
     * and the counters restart with each item, so start-up and seeks do not trigger it.
     */
    private fun checkDroppedFrames(rendered: Int, dropped: Int) {
        val prevRendered = lastRenderedForDrops
        val prevDropped = lastDropped
        lastRenderedForDrops = rendered
        lastDropped = dropped
        if (_slowDecoding.value || prevDropped < 0 || rendered < prevRendered || dropped < prevDropped) return
        if (!player.isPlaying || player.playbackState != Player.STATE_READY) {
            dropSamples.clear()
            playingSince = 0L
            return
        }
        // Right after a start or a rebuffer the decoder catches up and drops many frames even on streams it
        // handles well (seen with 4K 8-bit HEVC); only count once playback has settled.
        val now = SystemClock.elapsedRealtime()
        if (playingSince == 0L) playingSince = now
        if (now - playingSince < DROP_WARMUP_MS) return
        dropSamples.addLast((rendered - prevRendered) to (dropped - prevDropped))
        while (dropSamples.size > DROP_WINDOW_S) dropSamples.removeFirst()
        if (dropSamples.size < DROP_WINDOW_S) return
        val totalDropped = dropSamples.sumOf { it.second }
        val totalRendered = dropSamples.sumOf { it.first }
        val total = totalRendered + totalDropped
        // Both a large share dropped and a visibly low frame rate: a 50 fps stream shown at ~47 fps drops some
        // frames but looks smooth (many boxes output only 25-30 Hz anyway).
        val renderedFps = totalRendered.toFloat() / DROP_WINDOW_S
        if (total > 0 && totalDropped >= total * DROP_RATIO && renderedFps < MAX_CHOPPY_FPS) {
            Log.w(TAG, "Decoder falling behind: $totalDropped of $total frames dropped in ${DROP_WINDOW_S}s")
            ErrorLog.event("DECODER", "Slow decoding: $totalDropped of $total frames dropped in ${DROP_WINDOW_S}s (${_streamInfo.value.width}x${_streamInfo.value.height} ${_streamInfo.value.videoCodec.orEmpty()})\n${currentUrlForLog()}")
            _slowDecoding.value = true
        }
    }

    /** Primary URL first, then fallbacks (e.g. .ts when .m3u8 is not served). */
    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    private var attempt = 0
    private var everReady = false
    /** Source errors of the current item before it ever played. */
    private var sourceErrors = 0
    /** False while playing VOD: seeking allowed, no reconnect at the end. */
    private var live = true
    /** VOD start position; also where a reconnect resumes. */
    private var startPositionMs = 0L
    private val retryRunnable = Runnable { prepareCurrent() }

    /**
     * Some box decoders die inside the media service (seen with 4K HEVC on Allwinner) and ExoPlayer then
     * buffers forever without an error. Treat a long stall like a dropped stream.
     */
    private val stallRunnable: Runnable = Runnable {
        Log.w(TAG, "Stalled for ${STALL_TIMEOUT_MS} ms (live=$live, everReady=$everReady, bytes=${bytesLoaded.get()})")
        val loaded = bytesLoaded.get()
        // A slow server can take longer than the timeout just to deliver an MP4 index stored at the end of
        // the file. While data keeps arriving the movie is still starting, so wait (up to a limit).
        if (!live && !everReady && loaded - stallBytes >= MIN_PROGRESS_BYTES &&
            SystemClock.elapsedRealtime() - startedAt < VOD_START_LIMIT_MS
        ) {
            Log.i(TAG, "Still loading (${(loaded - stallBytes) / 1024} KB since last check), waiting")
            stallBytes = loaded
            mainHandler.postDelayed(stallRunnable, STALL_TIMEOUT_MS)
            return@Runnable
        }
        if (!everReady) urls.getOrNull(urlIndex)?.let(::probe)
        if (!live && !everReady) {
            player.stop()
            fail("timeout")
        } else {
            scheduleRetry("stall")
        }
    }

    private fun armStallWatchdog() {
        stallBytes = bytesLoaded.get()
        mainHandler.removeCallbacks(stallRunnable)
        mainHandler.postDelayed(stallRunnable, STALL_TIMEOUT_MS)
    }

    init {
        // Debug builds log tracks, formats and errors (tag "EventLogger") for diagnosing streams on real boxes.
        if (BuildConfig.DEBUG) player.addAnalyticsListener(EventLogger())
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
                val durationMs = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs
                if (mediaLoadData.mediaStartTimeMs == C.TIME_UNSET || durationMs <= 0 || loadEventInfo.bytesLoaded <= 0) return
                val kbps = (loadEventInfo.bytesLoaded * 8 / durationMs).toInt()
                segmentKbps = segmentKbps?.let { (it * 2 + kbps) / 3 } ?: kbps
                _streamInfo.value = _streamInfo.value.copy(bitrateKbps = segmentKbps!!)
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        if (_status.value !is PlaybackStatus.Reconnecting) _status.value = PlaybackStatus.Buffering
                        armStallWatchdog()
                    }
                    Player.STATE_READY -> {
                        mainHandler.removeCallbacks(stallRunnable)
                        attempt = 0
                        everReady = true
                        _status.value = PlaybackStatus.Playing
                    }
                    // A live stream never ends on purpose; the server closed it. VOD simply finished.
                    Player.STATE_ENDED -> when {
                        !live -> _status.value = PlaybackStatus.Ended
                        urls.isNotEmpty() -> scheduleRetry()
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.isEmpty) return
                logTracks(tracks)
                // Video exists but none was selected: either no decoder on this device handles its format, or it is
                // above the display limit. Say which instead of playing audio over a black screen.
                if (tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                    val reason = when {
                        !hasDecodableVideo(tracks) -> REASON_VIDEO_FORMAT
                        limitToDisplay -> REASON_RESOLUTION
                        else -> null
                    }
                    if (reason != null) {
                        mainHandler.removeCallbacks(retryRunnable)
                        mainHandler.removeCallbacks(stallRunnable)
                        player.stop()
                        fail(reason)
                        return
                    }
                }
                _audioOnly.value = live && !tracks.containsType(C.TRACK_TYPE_VIDEO) && tracks.containsType(C.TRACK_TYPE_AUDIO)
                _tracks.value = TrackOption.from(tracks)
                val info = StreamInfo.fromTracks(tracks)
                val (width, height) = pictureSize(player.videoSize)
                val previous = _streamInfo.value
                _streamInfo.value = info.copy(
                    width = if (width > 0) width else info.width,
                    height = if (height > 0) height else info.height,
                    // Keep what was measured when the stream does not declare it.
                    frameRate = if (info.frameRateFromStream) info.frameRate else previous.frameRate,
                    bitrateKbps = previous.bitrateKbps,
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val (width, height) = pictureSize(videoSize)
                if (width > 0) _streamInfo.value = _streamInfo.value.copy(width = width, height = height)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Playback error ${error.errorCodeName}", error)
                // Once per error type and item, so reconnect loops do not flood the log.
                if (loggedErrors.add(error.errorCode)) {
                    ErrorLog.event("PLAYBACK", "${error.errorCodeName}: ${error.cause?.message ?: error.message}\n${currentUrlForLog()}")
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                // Never played yet and a fallback exists: the format is probably not served, try the next one.
                if (!everReady && urlIndex < urls.lastIndex && isSourceError(error)) {
                    urlIndex++
                    prepareCurrent()
                    return
                }
                // The server answers, but not with playable media (error page, unsupported container...).
                // Retrying for minutes does not help: VOD gives up at once, live after a few tries.
                if (!everReady && isSourceError(error)) {
                    sourceErrors++
                    if (sourceErrors == 1) probe(urls[urlIndex])
                    if (!live || sourceErrors >= MAX_SOURCE_ERRORS) {
                        mainHandler.removeCallbacks(stallRunnable)
                        player.stop()
                        fail(REASON_SOURCE)
                        return
                    }
                }
                scheduleRetry(error.errorCodeName)
            }
        })
    }

    fun play(candidates: List<String>) = start(candidates, liveMode = true, startMs = 0)

    /** Plays a movie or episode from [startMs]. */
    fun playVod(url: String, startMs: Long) = start(listOf(url), liveMode = false, startMs = startMs)

    private fun start(candidates: List<String>, liveMode: Boolean, startMs: Long) {
        mainHandler.removeCallbacks(retryRunnable)
        live = liveMode
        startPositionMs = startMs
        urls = candidates
        urlIndex = 0
        attempt = 0
        startedAt = SystemClock.elapsedRealtime()
        everReady = false
        sourceErrors = 0
        loggedErrors.clear()
        // Close the old connection before opening a new one.
        player.stop()
        player.clearMediaItems()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        applyVideoLimit()
        _status.value = PlaybackStatus.Buffering
        resetStreamInfo()
        prepareCurrent()
    }

    /**
     * Caps video at the display resolution, but never below 1080p (many boxes report a 720p UI while
     * outputting 1080p). Adaptive streams then pick a lower variant; a stream that only exists above the
     * cap gets no video track, so a decoder that cannot cope (e.g. 4K HEVC on Allwinner) is never started.
     */
    fun setLimitToDisplay(enabled: Boolean) {
        limitToDisplay = enabled
        applyVideoLimit()
    }

    private fun applyVideoLimit() {
        val builder = trackSelector.buildUponParameters()
        if (limitToDisplay) {
            val display = Util.getCurrentDisplayModeSize(context)
            val w = maxOf(display.x, display.y, MIN_LIMIT_WIDTH)
            val h = maxOf(minOf(display.x, display.y), MIN_LIMIT_HEIGHT)
            builder.setMaxVideoSize(w, h).setExceedVideoConstraintsIfNecessary(false)
        } else {
            builder.clearVideoSizeConstraints().setExceedVideoConstraintsIfNecessary(true)
        }
        trackSelector.setParameters(builder)
    }

    fun selectTrack(option: TrackOption) {
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** Re-opens the current channel, e.g. when returning from background. */
    fun resume() {
        if (urls.isEmpty()) return
        if (!live && player.currentPosition > 0) startPositionMs = player.currentPosition
        attempt = 0
        _status.value = PlaybackStatus.Buffering
        resetStreamInfo()
        prepareCurrent()
    }

    fun stop() {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(stallRunnable)
        // Remember where VOD was, so resume() continues from there.
        if (!live && player.currentPosition > 0) startPositionMs = player.currentPosition
        mainHandler.removeCallbacks(rateTicker)
        _streamInfo.value = StreamInfo()
        _audioOnly.value = false
        player.stop()
        _status.value = PlaybackStatus.Idle
    }

    fun release() {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(stallRunnable)
        mainHandler.removeCallbacks(rateTicker)
        player.release()
    }

    private fun resetStreamInfo() {
        _streamInfo.value = StreamInfo()
        _audioOnly.value = false
        _tracks.value = emptyList()
        segmentKbps = null
        rateSamples.clear()
        frameSamples.clear()
        lastFrames = -1
        dropSamples.clear()
        lastRenderedForDrops = -1
        playingSince = 0L
        lastDropped = -1
        _slowDecoding.value = false
        lastBytes = bytesLoaded.get()
        mainHandler.removeCallbacks(rateTicker)
        mainHandler.postDelayed(rateTicker, 1000)
    }

    private fun prepareCurrent() {
        val url = urls.getOrNull(urlIndex) ?: return
        armStallWatchdog()
        if (live) player.setMediaItem(mediaItem(url)) else player.setMediaItem(mediaItem(url), startPositionMs)
        player.prepare()
        player.play()
    }

    private fun scheduleRetry(reason: String? = null) {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(stallRunnable)
        attempt++
        if (attempt > MAX_ATTEMPTS) {
            fail(reason ?: "stream ended")
            return
        }
        _status.value = PlaybackStatus.Reconnecting(attempt)
        if (!live && player.currentPosition > 0) startPositionMs = player.currentPosition
        val delayMs = (1000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(MAX_DELAY_MS)
        player.stop()
        mainHandler.postDelayed(retryRunnable, delayMs)
    }

    /**
     * Logs what the server actually returns for a URL the player cannot read (status, type, first bytes),
     * so unplayable items can be diagnosed from logcat. Credentials in the URL path are never logged.
     */
    private fun probe(url: String) {
        if (!url.startsWith("http", ignoreCase = true)) return
        val safeUrl = redact(url)
        Thread {
            runCatching {
                val request = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-1023").build()
                okHttp.newCall(request).execute().use { response ->
                    val source = response.body?.source() ?: return@use
                    source.request(PROBE_BYTES)
                    val head = source.buffer.snapshot().let { it.substring(0, minOf(it.size, PROBE_BYTES.toInt())) }
                    val text = head.utf8().map { if (it.isISOControl() && it != '\n') '.' else it }.joinToString("").take(300)
                    // Each hop of the redirect chain (host and address), oldest first.
                    val hops = generateSequence(response) { it.priorResponse }.toList().reversed().joinToString(" > ") { hop ->
                        val host = hop.request.url.host
                        val ip = runCatching { okHttp.dns.lookup(host).joinToString(",") { it.hostAddress ?: "?" } }.getOrDefault("?")
                        "${hop.code} $host[$ip]"
                    }
                    Log.w(TAG, "Probe hops: $hops")
                    Log.w(TAG, "Probe $safeUrl -> HTTP ${response.code}, type=${response.header("Content-Type")}, " +
                        "length=${response.header("Content-Length")}, range=${response.header("Content-Range")}, final=${redact(response.request.url.encodedPath)}\n" +
                        "hex=${head.substring(0, minOf(head.size, 16)).hex()}\ntext=$text")
                }
            }.onFailure { Log.w(TAG, "Probe $safeUrl failed: $it") }
        }.start()
    }

    /**
     * Picture size for the info bar. Some decoders (Allwinner HEVC) report the coded size including alignment
     * rows, e.g. 3840x2176 for a 3840x2160 stream; the format of the playing track has the real size.
     */
    private fun pictureSize(decoded: VideoSize): Pair<Int, Int> {
        val format = player.videoFormat
        return if (format != null && format.width > 0 && format.height > 0) format.width to format.height
        else decoded.width to decoded.height
    }

    /** True when at least one video track has a decoder on this device (possibly beyond its rated capabilities). */
    private fun hasDecodableVideo(tracks: Tracks): Boolean = tracks.groups.any { group ->
        group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { i ->
            group.getTrackSupport(i) >= C.FORMAT_EXCEEDS_CAPABILITIES
        }
    }

    /** One line per video/audio/subtitle track the stream offers, including ones the device cannot play. */
    private fun logTracks(tracks: Tracks) {
        tracks.groups.forEachIndexed { g, group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    Log.i(TAG, "Track video g$g/$i mime=${f.sampleMimeType} codecs=${f.codecs} ${f.width}x${f.height} " +
                        "fps=${f.frameRate} support=${Util.getFormatSupportString(group.getTrackSupport(i))} selected=${group.isTrackSelected(i)}")
                }
                return@forEachIndexed
            }
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                Log.i(TAG, "Track ${if (group.type == C.TRACK_TYPE_AUDIO) "audio" else "text"} g$g/$i " +
                    "mime=${f.sampleMimeType} codecs=${f.codecs} lang=${f.language} label=${f.label} ch=${f.channelCount} " +
                    "support=${Util.getFormatSupportString(group.getTrackSupport(i))} selected=${group.isTrackSelected(i)}")
            }
        }
    }

    /** Error codes already logged for the current item. */
    private val loggedErrors = HashSet<Int>()

    private fun currentUrlForLog(): String = urls.getOrNull(urlIndex)?.let(::redact).orEmpty()

    /** Gives up on the current item: shows [reason] and records it in the support log. */
    private fun fail(reason: String) {
        _status.value = PlaybackStatus.Failed(reason)
        ErrorLog.event("FAILED", "${if (live) "Live" else "VOD"}: $reason\n${currentUrlForLog()}")
    }

    private fun redact(url: String) = url.replace(CREDENTIALS_PATH, "/$1/***/***/")
        .replace(Regex("(username|password)=[^&]*"), "$1=***")

    private fun isSourceError(e: PlaybackException) = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> true
        else -> false
    }

    private fun mediaItem(url: String): MediaItem {
        val path = url.substringBefore('?').lowercase()
        val mime = when {
            url.startsWith("rtsp://", ignoreCase = true) -> MimeTypes.APPLICATION_RTSP
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            else -> null
        }
        return MediaItem.Builder()
            .setUri(url)
            .apply { if (mime != null) setMimeType(mime) }
            .apply { if (live) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build()) }
            .build()
    }

    companion object {
        /**
         * The default buffer (up to 50 s, sized for ~144 MB of samples) lives in the Java heap. A 4K stream
         * at 20+ Mb/s fills that in well under 50 s, and boxes often give apps only 128 MB, so the app died
         * with OutOfMemoryError. Cap the buffer by bytes at a quarter of the heap; time limits still apply
         * to normal streams, the byte cap only kicks in for high bitrates or a decoder that stopped consuming.
         */
        private fun loadControl(): DefaultLoadControl {
            val heap = Runtime.getRuntime().maxMemory()
            val target = (heap / 4).coerceIn(MIN_BUFFER_BYTES, MAX_BUFFER_BYTES).toInt()
            Log.i(TAG, "Buffer limit ${target / (1024 * 1024)} MB (heap ${heap / (1024 * 1024)} MB)")
            return DefaultLoadControl.Builder()
                .setTargetBufferBytes(target)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        }
        private const val MIN_BUFFER_BYTES = 16L * 1024 * 1024
        private const val MAX_BUFFER_BYTES = 64L * 1024 * 1024

        /** [PlaybackStatus.Failed] reason when the only video is above the display limit. */
        const val REASON_RESOLUTION = "resolution"
        /** [PlaybackStatus.Failed] reason when no decoder on the device handles the video format. */
        const val REASON_VIDEO_FORMAT = "video_format"
        /** [PlaybackStatus.Failed] reason when the server does not return playable media. */
        const val REASON_SOURCE = "source"

        private const val TAG = "LivePlayer"
        /** About 5 minutes of retries with the capped backoff before giving up. */
        private const val MAX_ATTEMPTS = 30
        /** Live: unreadable responses tolerated before giving up (servers sometimes hiccup at channel start). */
        private const val MAX_SOURCE_ERRORS = 3
        private const val PROBE_BYTES = 1024L
        private val CREDENTIALS_PATH = Regex("/(live|movie|series|timeshift)/[^/]+/[^/]+/")
        private const val MAX_DELAY_MS = 10_000L
        private const val RATE_WINDOW_S = 10
        private const val STALL_TIMEOUT_MS = 20_000L
        /** Less than this in a whole stall period counts as no progress. */
        private const val MIN_PROGRESS_BYTES = 256 * 1024L
        /** A movie that has not started after this long gives up even if data trickles in. */
        private const val VOD_START_LIMIT_MS = 120_000L
        private const val MIN_LIMIT_WIDTH = 1920
        private const val MIN_LIMIT_HEIGHT = 1080
        private const val FPS_WINDOW_S = 3
        /** Seconds of playback looked at, and the share of late-dropped frames that counts as too slow. */
        private const val DROP_WINDOW_S = 10
        private const val DROP_RATIO = 0.4
        /** Below this many shown frames per second the picture visibly stutters. */
        private const val MAX_CHOPPY_FPS = 20f
        /** Playback time ignored after each start or rebuffer before frames are counted. */
        private const val DROP_WARMUP_MS = 10_000L
        private val COMMON_FPS = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

        /** Measured rates jitter; snap to the nearest broadcast rate when close. */
        private fun snapFps(measured: Float): Float =
            COMMON_FPS.minByOrNull { kotlin.math.abs(it - measured) }?.takeIf { kotlin.math.abs(it - measured) < 1.5f }
                ?: kotlin.math.round(measured)
    }
}
