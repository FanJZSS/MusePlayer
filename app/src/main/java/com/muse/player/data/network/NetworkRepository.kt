package com.muse.player.data.network

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 局域网服务器配置与远程目录访问入口。
 *
 * 播放地址使用自定义 scheme（muse+smb / muse+ftp / muse+webdav），
 * 真正的服务器凭据只保存在本机，不会出现在播放地址里。
 */
object NetworkRepository {
    private const val PREFS = "muse_network"
    private const val KEY_SERVERS = "servers"

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "m4v", "ts", "m2ts",
        "mpg", "mpeg", "rmvb", "rm", "3gp", "ogv", "vob", "divx", "f4v", "asf", "mts"
    )

    /** 与 SubtitleFinder 本地扫描保持一致的字幕扩展名 */
    val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "ttml", "dfxp")

    private lateinit var sp: SharedPreferences
    private val _servers = MutableStateFlow<List<NetworkServer>>(emptyList())
    val servers: StateFlow<List<NetworkServer>> = _servers.asStateFlow()

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _servers.value = read()
    }

    fun serverFor(id: String): NetworkServer? = _servers.value.firstOrNull { it.id == id }

    fun save(server: NetworkServer): NetworkServer {
        val stored = if (server.id.isBlank()) {
            server.copy(id = UUID.randomUUID().toString())
        } else {
            server
        }
        val list = _servers.value.filterNot { it.id == stored.id } + stored
        _servers.value = list
        persist(list)
        return stored
    }

    fun delete(id: String) {
        val list = _servers.value.filterNot { it.id == id }
        if (list.size == _servers.value.size) return
        _servers.value = list
        persist(list)
    }

    /** 列出远程目录（文件夹在前，名称升序） */
    suspend fun list(server: NetworkServer, path: String): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            NetworkClients.list(server, normalize(path)).sortedWith(
                compareByDescending<RemoteFile> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
        }

    fun isVideoFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    fun isSubtitleFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS

    /**
     * 从同一目录的条目中找出与视频同名的字幕。
     *
     * 规则与本地 [com.muse.player.data.SubtitleFinder] 一致：去掉扩展名后
     * 以视频名开头即可（兼容 `movie.zh.srt`、`movie.en.ass` 这类语言后缀）。
     * 目录列表在浏览时已经拿到，因此不需要为字幕额外发一次网络请求。
     */
    fun matchingSubtitles(videoName: String, entries: List<RemoteFile>): List<RemoteFile> {
        val baseName = videoName.substringBeforeLast('.', videoName)
        if (baseName.isBlank()) return emptyList()
        return entries
            .filter { !it.isDirectory && isSubtitleFile(it.name) }
            .filter { entry ->
                // 排除 ".srt" 这类没有文件名的条目：基名为空时 startsWith("") 恒为真
                val entryBase = entry.name.substringBeforeLast('.', "")
                entryBase.isNotBlank() && entryBase.startsWith(baseName, ignoreCase = true)
            }
            .sortedBy { it.name }
            .take(8)
    }

    /** 生成播放地址，形如 muse+smb://<serverId>/dir/movie.mkv */
    fun buildPlayUri(server: NetworkServer, relativePath: String): String {
        val builder = Uri.Builder()
            .scheme(server.type.scheme)
            .authority(server.id)
        segments(relativePath).forEach { builder.appendPath(it) }
        return builder.build().toString()
    }

    /** 解析播放地址，返回服务器与相对路径 */
    fun resolve(uri: Uri): Pair<NetworkServer, String>? {
        val type = ServerType.fromScheme(uri.scheme) ?: return null
        val id = uri.authority ?: return null
        val server = serverFor(id)?.takeIf { it.type == type } ?: return null
        return server to uri.pathSegments.joinToString("/")
    }

    /** 拼接相对路径 */
    fun joinPath(parent: String, name: String): String = when {
        parent.isBlank() -> name
        else -> "${normalize(parent)}/$name"
    }

    fun normalize(path: String): String = segments(path).joinToString("/")

    /** 服务器根路径（新建浏览时使用） */
    fun rootPath(server: NetworkServer): String = normalize(server.path)

    fun segments(path: String): List<String> = path.split('/').filter { it.isNotBlank() }

    /** SMB 文件地址，目录需要以 / 结尾 */
    fun smbUrl(server: NetworkServer, relativePath: String, directory: Boolean): String {
        val builder = StringBuilder("smb://").append(server.host)
        if (server.port != ServerType.SMB.defaultPort) builder.append(':').append(server.port)
        val segs = segments(relativePath)
        if (segs.isEmpty()) {
            builder.append('/')
        } else {
            segs.forEach { builder.append('/').append(it) }
            if (directory) builder.append('/')
        }
        return builder.toString()
    }

    /** WebDAV 地址，目录需要以 / 结尾 */
    fun webDavUrl(server: NetworkServer, relativePath: String, directory: Boolean): String {
        val scheme = if (server.port == 443) "https" else "http"
        val builder = Uri.Builder()
            .scheme(scheme)
            .encodedAuthority("${server.host}:${server.port}")
        segments(relativePath).forEach { builder.appendPath(it) }
        val url = builder.build().toString()
        return if (directory) "$url/" else url
    }

    /** WebDAV Basic 认证头 */
    fun basicAuthHeader(server: NetworkServer): String? {
        if (server.username.isBlank()) return null
        val raw = "${server.username}:${server.password}"
        return "Basic " + Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    }

    private fun persist(list: List<NetworkServer>) {
        val array = JSONArray()
        list.forEach { server ->
            array.put(
                JSONObject().apply {
                    put("id", server.id)
                    put("type", server.type.name)
                    put("name", server.name)
                    put("host", server.host)
                    put("port", server.port)
                    put("username", server.username)
                    put("password", server.password)
                    put("path", server.path)
                }
            )
        }
        sp.edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    private fun read(): List<NetworkServer> {
        val raw = sp.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching {
                    ServerType.valueOf(obj.optString("type", ServerType.SMB.name))
                }.getOrDefault(ServerType.SMB)
                NetworkServer(
                    id = obj.optString("id"),
                    type = type,
                    name = obj.optString("name"),
                    host = obj.optString("host"),
                    port = obj.optInt("port", type.defaultPort),
                    username = obj.optString("username"),
                    password = obj.optString("password"),
                    path = obj.optString("path")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
