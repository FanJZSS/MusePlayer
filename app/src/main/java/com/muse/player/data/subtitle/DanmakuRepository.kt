package com.muse.player.data.subtitle

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
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
@OptIn(UnstableApi::class)
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
        // 含 % 等字符的路径会让 Uri.parse 解析失败，先按原字符串直接当文件路径处理
        val bytes = readLocal(path) ?: readRemote(context, path) ?: return null
        return decode(bytes)
    }

    /** 先尝试当作本地文件读取；不是本地文件时返回 null */
    private fun readLocal(path: String): ByteArray? {
        val file = when {
            path.startsWith("file://", ignoreCase = true) -> {
                // file:// 表示本地文件，取真实路径
                val parsed = runCatching { Uri.parse(path).path }.getOrNull() ?: return null
                File(parsed)
            }

            // 其它情况一律先按原始字符串当路径，避免 Uri.parse 对 % 等字符的解释
            else -> File(path)
        }
        return try {
            if (!file.isFile) return null
            if (file.length() > MAX_BYTES) return null
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    /** 通过播放器数据源读取远程文件（muse+smb / muse+ftp / muse+webdav 等） */
    private fun readRemote(context: Context, path: String): ByteArray? {
        val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        // 只处理播放器数据源认识的 scheme，其它（如 content://）交给上层
        if (!scheme.startsWith("muse+") && scheme != "http" && scheme != "https") return null

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
