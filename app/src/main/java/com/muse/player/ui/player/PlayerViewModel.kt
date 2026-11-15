package com.muse.player.ui.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.muse.player.data.HistoryRepository
import com.muse.player.data.PlayEntry
import com.muse.player.data.SettingsRepository
import com.muse.player.data.SubtitleFinder
import com.muse.player.playback.PlaybackService
import com.muse.player.playback.network.MuseDataSource
import com.muse.player.util.NetworkTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class TrackOption(
    val label: String,
    val selected: Boolean
)

data class PlayerUiState(
    val connected: Boolean = false,
    val title: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val speed: Float = 1f,
    val aspectMode: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesAvailable: Boolean = false,
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    val error: String? = null
)

@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    var player: MediaController? = null
        private set

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingStart: PendingStart? = null
    private var ticker: Job? = null
    private var startJob: Job? = null
    private var tickCount = 0
    private var entries: List<PlayEntry> = emptyList()

    // 已经按 HLS 重试过的地址，避免反复重试
    private val hlsRetriedUris = mutableSetOf<String>()

    private data class PendingStart(val entries: List<PlayEntry>, val index: Int, val positionMs: Long)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (retryAsHls(error)) return
            _state.update {
                it.copy(
                    error = friendlyError(error),
                    isBuffering = false
                )
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update {
                it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
            }
        }
    }

    init {
        connect()
    }

    private fun connect() {
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val controller = future.get()
                    player = controller
                    controller.addListener(listener)
                    _state.update { it.copy(connected = true) }
                    pendingStart?.let {
                        pendingStart = null
                        start(it.entries, it.index, it.positionMs)
                    }
                    startTicker()
                } catch (_: Throwable) {
                    _state.update { it.copy(error = "无法连接播放服务") }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /** 设置播放队列并开始播放 */
    fun start(entries: List<PlayEntry>, index: Int, positionMs: Long) {
        val controller = player
        if (controller == null) {
            pendingStart = PendingStart(entries, index, positionMs)
            return
        }
        if (entries.isEmpty()) return
        this.entries = entries
        val safeIndex = index.coerceIn(0, entries.lastIndex)
        val startPosition = positionMs.coerceAtLeast(0L)
        // 字幕文件查找涉及磁盘遍历，放到 IO 线程，避免拖慢起播
        startJob?.cancel()
        startJob = viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                entries.mapIndexed { i, entry -> buildMediaItem(entry, probeMime = i == safeIndex) }
            }
            val current = player ?: return@launch
            current.setMediaItems(items, safeIndex, startPosition)
            current.prepare()
            current.playWhenReady = true
            _state.update { it.copy(error = null) }
        }
    }

    private fun buildMediaItem(entry: PlayEntry, probeMime: Boolean = false): MediaItem {
        val metadata = MediaMetadata.Builder().setArtist("Muse Player")
        // 网络视频的标题若只是占位（通用文件名或域名），不写死标题，
        // 让播放器读取流内的元数据（如 ID3 标题）来展示真实名称
        if (!(entry.isNetwork && NetworkTitles.isPlaceholder(entry.title))) {
            metadata.setTitle(entry.title)
        }
        val builder = MediaItem.Builder()
            .setUri(entry.uri)
            .setMediaMetadata(metadata.build())

        if (entry.isNetwork) {
            val mime = inferMimeType(entry.uri) ?: if (probeMime) probeMimeType(entry.uri) else null
            mime?.let { builder.setMimeType(it) }
        }

        val subtitlePaths = LinkedHashSet<String>()
        if (!entry.isNetwork && SettingsRepository.state.value.autoSubtitles) {
            subtitlePaths += SubtitleFinder.findFor(entry.path)
        }
        subtitlePaths += entry.subtitleFiles

        val configs = subtitlePaths.mapNotNull { subtitleConfig(it) }
        if (configs.isNotEmpty()) {
            builder.setSubtitleConfigurations(configs)
        }
        return builder.build()
    }

    private fun subtitleConfig(path: String): MediaItem.SubtitleConfiguration? {
        val file = File(path)
        if (!file.exists()) return null
        val mime = when (file.extension.lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> return null
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(mime)
            .setLabel(file.name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private fun inferMimeType(url: String): String? {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            clean.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            clean.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            clean.endsWith(".mp4") || clean.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            clean.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            clean.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    /**
     * 地址没有可用后缀时（TVBox 等应用常给接口/代理地址），
     * 发一个 2KB 的探测请求，按 Content-Type 与文件头判断真实格式。
     */
    private fun probeMimeType(url: String): String? {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", MuseDataSource.USER_AGENT)
                setRequestProperty("Range", "bytes=0-2047")
            }
            try {
                val contentType = connection.contentType?.lowercase().orEmpty()
                val head = ByteArray(2048)
                var length = 0
                connection.inputStream.use { input ->
                    while (length < head.size) {
                        val read = input.read(head, length, head.size - length)
                        if (read <= 0) break
                        length += read
                    }
                }
                detectMimeType(contentType, head, length)
            } finally {
                connection.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun detectMimeType(contentType: String, head: ByteArray, length: Int): String? {
        if (length <= 0) return null
        val text = String(head, 0, length, Charsets.ISO_8859_1)
        val isTs = length > 376 &&
            (head[0].toInt() and 0xFF) == 0x47 && (head[188].toInt() and 0xFF) == 0x47
        val isMatroska = length > 4 &&
            (head[0].toInt() and 0xFF) == 0x1A &&
            (head[1].toInt() and 0xFF) == 0x45 &&
            (head[2].toInt() and 0xFF) == 0xDF &&
            (head[3].toInt() and 0xFF) == 0xA3
        return when {
            text.startsWith("#EXTM3U") || contentType.contains("mpegurl") ->
                MimeTypes.APPLICATION_M3U8
            contentType.contains("dash+xml") ||
                (text.trimStart().startsWith("<?xml") && text.contains("<MPD")) ->
                MimeTypes.APPLICATION_MPD
            isTs || contentType.contains("mp2t") -> MimeTypes.VIDEO_MP2T
            isMatroska -> MimeTypes.VIDEO_MATROSKA
            length > 8 && text.substring(4, 8) == "ftyp" -> MimeTypes.VIDEO_MP4
            contentType.contains("video/mp4") -> MimeTypes.VIDEO_MP4
            contentType.contains("video/webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    /** 无法识别容器且地址是网络地址时，按 HLS 重新构建一次并重试（每个地址只尝试一次） */
    private fun retryAsHls(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) return false
        val controller = player ?: return false
        val item = controller.currentMediaItem ?: return false
        val uri = item.localConfiguration?.uri?.toString() ?: return false
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) return false
        if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) return false
        if (!hlsRetriedUris.add(uri)) return false

        val index = controller.currentMediaItemIndex
        val position = controller.currentPosition
        val items = (0 until controller.mediaItemCount)
            .map { controller.getMediaItemAt(it) }
            .toMutableList()
        if (index !in items.indices) return false
        items[index] = item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build()
        controller.setMediaItems(items, index, position)
        controller.prepare()
        controller.playWhenReady = true
        _state.update { it.copy(error = null, isBuffering = true) }
        return true
    }

    /** 把错误码翻译成可读原因，网络类错误附带底层异常信息，便于判断是源站问题还是链接失效 */
    private fun friendlyError(error: PlaybackException): String {
        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "网络请求失败"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "无法连接到服务器"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连接服务器超时"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "服务器返回的内容不是视频"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器返回错误状态码"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在或地址已失效"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有访问权限"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "系统禁止播放明文 HTTP 地址"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "视频文件损坏或格式异常"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "无法识别视频格式（该地址可能不是直链视频）"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "播放列表格式异常"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "不支持该播放列表格式"
            else -> error.errorCodeName
        }
        val detail = if (error.errorCode in 2000..2999) {
            generateSequence(error.cause) { it.cause }
                .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull()
        } else {
            null
        }
        return if (detail == null) "播放失败：$reason" else "播放失败：$reason（${detail.take(80)}）"
    }

    private fun refresh(player: Player) {
        val audioTracks = mutableListOf<TrackOption>()
        val subtitleTracks = mutableListOf<TrackOption>()
        player.currentTracks.groups.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        audioTracks += TrackOption(
                            label = trackLabel(format.label, format.language, format.codecs, audioTracks.size, true),
                            selected = group.isTrackSelected(i)
                        )
                    }
                }

                C.TRACK_TYPE_TEXT -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        subtitleTracks += TrackOption(
                            label = trackLabel(format.label, format.language, format.codecs, subtitleTracks.size, false),
                            selected = group.isTrackSelected(i)
                        )
                    }
                }
            }
        }

        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        // 优先展示流内元数据里的真实标题，占位标题时不覆盖
        val runtimeTitle = player.mediaMetadata.title?.toString()
            ?.takeIf { it.isNotBlank() && !NetworkTitles.isPlaceholder(it) }
        val itemTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()
            ?.takeIf { it.isNotBlank() }

        _state.update {
            it.copy(
                connected = true,
                title = runtimeTitle
                    ?: itemTitle
                    ?: entries.getOrNull(player.currentMediaItemIndex)?.title
                    ?: "",
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = duration,
                hasPrev = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
                speed = player.playbackParameters.speed,
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                subtitlesAvailable = !textDisabled && subtitleTracks.any { it.selected },
                itemIndex = player.currentMediaItemIndex,
                itemCount = player.mediaItemCount
            )
        }
    }

    private fun trackLabel(
        label: String?,
        language: String?,
        codecs: String?,
        index: Int,
        isAudio: Boolean
    ): String {
        val base = label
            ?.takeIf { it.isNotBlank() }
            ?: language?.takeIf { it.isNotBlank() }?.uppercase()
            ?: "${if (isAudio) "音轨" else "字幕"} ${index + 1}"
        return if (codecs.isNullOrBlank()) base else "$base · $codecs"
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val controller = player
                if (controller != null) {
                    val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                    _state.update {
                        it.copy(
                            positionMs = controller.currentPosition.coerceAtLeast(0),
                            durationMs = duration,
                            isPlaying = controller.isPlaying,
                            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
                            hasPrev = controller.hasPreviousMediaItem(),
                            hasNext = controller.hasNextMediaItem()
                        )
                    }
                    tickCount++
                    if (tickCount >= 10) {
                        tickCount = 0
                        if (controller.isPlaying) saveCurrentProgress()
                    }
                }
                delay(500)
            }
        }
    }

    fun togglePlay() {
        val controller = player ?: return
        if (controller.isPlaying) {
            controller.pause()
            saveCurrentProgress()
        } else {
            if (controller.playbackState == Player.STATE_ENDED) {
                controller.seekTo(0)
            }
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = player ?: return
        val target = positionMs.coerceAtLeast(0L)
        controller.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun seekBy(deltaMs: Long) {
        val controller = player ?: return
        val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val target = (controller.currentPosition + deltaMs).coerceIn(0L, duration)
        controller.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun playNext() {
        val controller = player ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        }
    }

    fun playPrevious() {
        val controller = player ?: return
        if (controller.currentPosition > 5_000) {
            controller.seekTo(0)
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        } else {
            controller.seekTo(0)
        }
    }

    fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
        _state.update { it.copy(speed = speed) }
    }

    /** 循环模式：关闭 → 列表循环 → 单曲循环 → 关闭 */
    fun cycleRepeatMode() {
        val controller = player ?: return
        val next = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = next
        _state.update { it.copy(repeatMode = next) }
    }

    /** 切换随机播放 */
    fun toggleShuffle() {
        val controller = player ?: return
        val enabled = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = enabled
        _state.update { it.copy(shuffleEnabled = enabled) }
    }

    fun setAspectMode(mode: Int) {
        _state.update { it.copy(aspectMode = mode) }
    }

    fun selectAudioTrack(index: Int) {
        val controller = player ?: return
        val groups = controller.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val group = groups.getOrNull(index) ?: return
        controller.trackSelectionParameters = controller.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    fun selectSubtitle(index: Int) {
        val controller = player ?: return
        val builder = controller.trackSelectionParameters.buildUpon()
        if (index < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val groups = controller.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val group = groups.getOrNull(index) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        controller.trackSelectionParameters = builder.build()
    }

    /** 加载用户选择的外挂字幕，保持当前进度 */
    fun addSubtitle(uri: Uri, displayName: String) {
        val controller = player ?: return
        val index = controller.currentMediaItemIndex
        if (index !in 0 until controller.mediaItemCount) return
        val current = controller.getMediaItemAt(index)
        val mime = when (displayName.substringAfterLast('.', "").lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLabel(displayName)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val local = current.localConfiguration ?: return
        val updatedItem = MediaItem.Builder()
            .setUri(local.uri)
            .setMimeType(local.mimeType)
            .setMediaMetadata(current.mediaMetadata)
            .setSubtitleConfigurations(local.subtitleConfigurations + config)
            .build()
        val items = (0 until controller.mediaItemCount)
            .map { controller.getMediaItemAt(it) }
            .toMutableList()
        if (index !in items.indices) return
        items[index] = updatedItem
        val position = controller.currentPosition
        val wasPlaying = controller.isPlaying
        controller.setMediaItems(items, index, position)
        controller.prepare()
        if (wasPlaying) controller.play()
    }

    fun retry() {
        val controller = player ?: return
        _state.update { it.copy(error = null) }
        controller.prepare()
        controller.play()
    }

    fun pauseAndSave() {
        player?.pause()
        saveCurrentProgress()
    }

    fun saveProgress() {
        saveCurrentProgress()
    }

    private fun saveCurrentProgress() {
        val controller = player ?: return
        val item = controller.currentMediaItem ?: return
        val index = controller.currentMediaItemIndex
        val entry = entries.getOrNull(index)
        // 网络 / 局域网视频同样写入历史记录，方便下次继续播放
        val uri = item.localConfiguration?.uri?.toString()
            ?: entry?.uri
            ?: return
        val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: entry?.durationMs
            ?: 0L
        val position = controller.currentPosition
        // 优先使用流内元数据标题；旧历史里的 index 之类占位标题会在重新播放时被替换
        val title = controller.mediaMetadata.title?.toString()
            ?.takeIf { it.isNotBlank() && !NetworkTitles.isPlaceholder(it) }
            ?: item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: entry?.title?.takeIf { it.isNotBlank() && !NetworkTitles.isPlaceholder(it) }
            ?: entry?.uri?.takeIf { it.startsWith("http") }?.let { NetworkTitles.of(it) }
            ?: entry?.title
            ?: ""
        if (position > 0) {
            HistoryRepository.record(uri, title, position, duration)
        }
    }

    override fun onCleared() {
        saveCurrentProgress()
        ticker?.cancel()
        ticker = null
        player?.removeListener(listener)
        player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onCleared()
    }
}
