package com.muse.player.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    suspend fun loadVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val result = ArrayList<VideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATA
        )
        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: "未知视频"
                    result += VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = name.substringBeforeLast('.').ifBlank { name },
                        path = if (dataIdx >= 0) cursor.getString(dataIdx) else null,
                        durationMs = cursor.getLong(durationIdx),
                        size = cursor.getLong(sizeIdx),
                        dateAdded = cursor.getLong(dateIdx) * 1000L,
                        bucketId = cursor.getString(bucketIdIdx) ?: "0",
                        bucketName = cursor.getString(bucketNameIdx) ?: "未命名文件夹",
                        width = cursor.getInt(widthIdx),
                        height = cursor.getInt(heightIdx),
                        mimeType = cursor.getString(mimeIdx)
                    )
                }
            }
        } catch (_: Exception) {
            // 权限缺失或 MediaStore 异常时返回空列表
        }
        result
    }
}
