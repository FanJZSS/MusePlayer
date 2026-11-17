package com.muse.player.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 视频缩略图加载器：内存缓存 + 磁盘缓存。
 */
object ThumbnailLoader {

    // 按占用内存计量（约 32MB），够放下几百张缩略图，反复切换分栏不必重新解码
    private val memory = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(context: Context, uri: Uri, id: Long): Bitmap? = withContext(Dispatchers.IO) {
        val key = id.toString()
        memory.get(key)?.let { return@withContext it }

        val cacheFile = File(context.cacheDir, "thumbs/$key.jpg")
        if (cacheFile.exists()) {
            BitmapFactory.decodeFile(cacheFile.absolutePath)?.let {
                memory.put(key, it)
                return@withContext it
            }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    480,
                    270
                )
            } else {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (bitmap != null) {
                memory.put(key, bitmap)
                writeCache(cacheFile, bitmap)
            }
            bitmap
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeCache(file: File, bitmap: Bitmap) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
        } catch (_: Exception) {
        }
    }
}
