package com.muse.player.data

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** 一个可播放条目（本地视频或网络串流） */
@Parcelize
data class PlayEntry(
    val uri: String,
    val title: String,
    val id: Long = -1L,
    val durationMs: Long = 0L,
    val path: String? = null,
    val isNetwork: Boolean = false,
    val subtitleFiles: List<String> = emptyList()
) : Parcelable

/** MediaStore 中的视频 */
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val path: String?,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long,
    val bucketId: String,
    val bucketName: String,
    val width: Int,
    val height: Int,
    val mimeType: String?
) {
    fun toPlayEntry(subtitles: List<String> = emptyList()) = PlayEntry(
        uri = uri.toString(),
        title = title,
        id = id,
        durationMs = durationMs,
        path = path,
        subtitleFiles = subtitles
    )
}

data class FolderItem(
    val bucketId: String,
    val name: String,
    val count: Int,
    val cover: VideoItem?
)

data class HistoryEntry(
    val uri: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val playedAt: Long
)

enum class SortField(val label: String) {
    DATE("按日期"),
    NAME("按名称"),
    DURATION("按时长"),
    SIZE("按大小"),
    FOLDER("按文件夹")
}
