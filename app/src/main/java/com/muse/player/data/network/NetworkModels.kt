package com.muse.player.data.network

/** 局域网存储协议类型 */
enum class ServerType(
    val label: String,
    val defaultPort: Int,
    val scheme: String
) {
    SMB("SMB", 445, "muse+smb"),
    FTP("FTP", 21, "muse+ftp"),
    WEBDAV("WebDAV", 80, "muse+webdav");

    companion object {
        fun fromScheme(scheme: String?): ServerType? =
            entries.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }
    }
}

/** 局域网服务器配置 */
data class NetworkServer(
    val id: String = "",
    val type: ServerType = ServerType.SMB,
    val name: String = "",
    val host: String = "",
    val port: Int = 445,
    val username: String = "",
    val password: String = "",
    val path: String = ""
) {
    /** 用于界面展示的地址：host[:port]/path */
    val displayAddress: String
        get() = buildString {
            append(host)
            if (port != type.defaultPort) append(':').append(port)
            val clean = path.trim('/')
            if (clean.isNotEmpty()) append('/').append(clean)
        }
}

/** 远程目录中的条目 */
data class RemoteFile(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modified: Long = 0L,
    val uri: String = ""
)
