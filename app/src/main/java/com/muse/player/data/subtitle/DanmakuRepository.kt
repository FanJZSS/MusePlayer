package com.muse.player.data.subtitle

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.muse.player.playback.network.MuseDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 弹幕字幕后端：把 ASS 文件读成文本再交给 [AssDanmakuParser]。
 *
 * 网络地址（muse+smb / muse+ftp / muse+webdav）复用播放器同一套数据源，
 * 因此不需要在这里重复实现三种协议的读取逻辑。
 */
object DanmakuRepository {

    /** 最大读取字节数，防止误加载超大文件 */
    private const val MAX_BYTES = 12 * 1024 * 1024

    /**
     * 读取并解析弹幕字幕。
     * 返回 null 表示该地址不是可用的 ASS 弹幕，调用方应回退到普通字幕管线。
     */
    suspend fun load(context: Context, path: String?): DanmakuTrack? {
        if (path.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val raw = readText(context, path) ?: return@runCatching null
                AssDanmakuParser.parse(raw)
            }.getOrNull()
        }
    }

    private fun readText(context: Context, path: String): String? {
        val uri = Uri.parse(path)
        val scheme = uri.scheme?.lowercase()
        val bytes = if (scheme == null || scheme == "file") {
            val file = File(if (scheme == null) path else uri.path.orEmpty())
            if (!file.exists() || file.length() > MAX_BYTES) return null
            file.readBytes()
        } else {
            readRemote(context, uri) ?: return null
        }
        return decode(bytes)
    }

    /** 通过播放器数据源读取远程文件 */
    private fun readRemote(context: Context, uri: Uri): ByteArray? {
        val dataSource = MuseDataSource(context)
        return try {
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setKey(uri.toString())
                .build()
            dataSource.open(spec)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read <= 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BYTES) return null
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { dataSource.close() }
        }
    }

    /**
     * 按 BOM 或 UTF-8 解码；部分导出工具用 GBK，需要回退。
     */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        // U+FFFD 说明不是合法 UTF-8，改用 GBK（国内字幕常见）
        if (utf8.contains('\uFFFD')) {
            runCatching { String(bytes, charset("GBK")) }.getOrNull()?.let { return it }
        }
        return utf8
    }
}
