package com.muse.player.data.network

import android.net.Uri
import android.util.Xml
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * 各协议的目录列举实现。所有方法都在 IO 线程调用。
 */
internal object NetworkClients {

    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
            "<D:displayname/><D:getcontentlength/><D:getlastmodified/><D:resourcetype/>" +
            "</D:prop></D:propfind>"

    private val webDavClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun list(server: NetworkServer, path: String): List<RemoteFile> = when (server.type) {
        ServerType.SMB -> listSmb(server, path)
        ServerType.FTP -> listFtp(server, path)
        ServerType.WEBDAV -> listWebDav(server, path)
    }

    // ---------------------------------------------------------------- SMB

    fun smbContext(server: NetworkServer): CIFSContext {
        val props = Properties()
        props["jcifs.smb.client.minVersion"] = "SMB1"
        props["jcifs.smb.client.maxVersion"] = "SMB311"
        props["jcifs.smb.client.connTimeout"] = "15000"
        props["jcifs.smb.client.responseTimeout"] = "20000"
        props["jcifs.smb.client.soTimeout"] = "25000"
        val context = BaseContext(PropertyConfiguration(props))
        if (server.username.isBlank()) return context

        val raw = server.username
        val slash = raw.indexOf('\\')
        val domain = if (slash > 0) raw.substring(0, slash) else null
        val user = if (slash > 0) raw.substring(slash + 1) else raw
        return context.withCredentials(
            NtlmPasswordAuthenticator(domain, user, server.password)
        )
    }

    private fun listSmb(server: NetworkServer, path: String): List<RemoteFile> {
        val context = smbContext(server)
        try {
            val url = NetworkRepository.smbUrl(server, path, directory = true)
            val dir = SmbFile(url, context)
            if (!dir.exists()) throw IOException("无法访问路径：$path")
            val files = dir.listFiles() ?: return emptyList()
            return files.mapNotNull { file ->
                val name = file.name.trimEnd('/').trim()
                if (name.isBlank() || name == "." || name == "..") return@mapNotNull null
                val isDir = runCatching { file.isDirectory }.getOrDefault(false)
                RemoteFile(
                    name = name,
                    relativePath = NetworkRepository.joinPath(path, name),
                    isDirectory = isDir,
                    size = if (isDir) 0L else runCatching { file.length() }.getOrDefault(0L),
                    modified = runCatching { file.lastModified() }.getOrDefault(0L),
                    uri = NetworkRepository.buildPlayUri(
                        server,
                        NetworkRepository.joinPath(path, name)
                    )
                )
            }
        } finally {
            runCatching { context.close() }
        }
    }

    // ---------------------------------------------------------------- FTP

    private fun listFtp(server: NetworkServer, path: String): List<RemoteFile> {
        val ftp = FTPClient()
        try {
            ftp.connect(server.host, server.port)
            ftp.setAutodetectUTF8(true)
            val user = server.username.ifBlank { "anonymous" }
            val pass = if (server.username.isBlank()) "anonymous@" else server.password
            if (!ftp.login(user, pass)) {
                throw IOException("FTP 登录失败，请检查账号密码")
            }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            if (path.isNotBlank() && !ftp.changeWorkingDirectory(path)) {
                throw IOException("无法进入目录：$path")
            }
            val files = ftp.listFiles() ?: throw IOException("FTP 目录读取失败")
            return files.filter { it.name != "." && it.name != ".." }.map { file ->
                RemoteFile(
                    name = file.name,
                    relativePath = NetworkRepository.joinPath(path, file.name),
                    isDirectory = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.size,
                    modified = runCatching { file.timestamp?.timeInMillis ?: 0L }.getOrDefault(0L),
                    uri = NetworkRepository.buildPlayUri(
                        server,
                        NetworkRepository.joinPath(path, file.name)
                    )
                )
            }
        } finally {
            runCatching { if (ftp.isConnected) ftp.logout() }
            runCatching { if (ftp.isConnected) ftp.disconnect() }
        }
    }

    // ------------------------------------------------------------- WebDAV

    private fun listWebDav(server: NetworkServer, path: String): List<RemoteFile> {
        val dirUrl = NetworkRepository.webDavUrl(server, path, directory = true)
        val builder = Request.Builder()
            .url(dirUrl)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
        NetworkRepository.basicAuthHeader(server)?.let { builder.header("Authorization", it) }

        webDavClient.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("WebDAV 返回 ${response.code}，请检查地址与账号")
            }
            val dirPath = Uri.parse(dirUrl).path?.trimEnd('/').orEmpty()
            return parseDav(body).mapNotNull { entry ->
                val hrefPath = Uri.parse(entry.href).path?.trimEnd('/').orEmpty()
                if (hrefPath.isBlank() || hrefPath == dirPath) return@mapNotNull null
                val rawName = hrefPath.substringAfterLast('/')
                if (rawName.isBlank()) return@mapNotNull null
                val name = entry.displayName?.takeIf { it.isNotBlank() } ?: rawName
                val childPath = NetworkRepository.joinPath(path, rawName)
                RemoteFile(
                    name = name,
                    relativePath = childPath,
                    isDirectory = entry.isCollection,
                    size = entry.size,
                    modified = entry.modified,
                    uri = NetworkRepository.buildPlayUri(server, childPath)
                )
            }
        }
    }

    private data class DavEntry(
        var href: String = "",
        var isCollection: Boolean = false,
        var size: Long = 0L,
        var displayName: String? = null,
        var modified: Long = 0L
    )

    private fun parseDav(xml: String): List<DavEntry> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val result = mutableListOf<DavEntry>()
        var current: DavEntry? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (localName(parser.name)) {
                    "response" -> current = DavEntry()
                    "href" -> current?.href = readText(parser)
                    "collection" -> current?.isCollection = true
                    "getcontentlength" -> {
                        current?.size = readText(parser).toLongOrNull() ?: 0L
                    }

                    "getlastmodified" -> {
                        current?.modified = parseHttpDate(readText(parser))
                    }

                    "displayname" -> {
                        current?.displayName = readText(parser)
                    }
                }
            } else if (event == XmlPullParser.END_TAG && localName(parser.name) == "response") {
                current?.let { if (it.href.isNotBlank()) result += it }
                current = null
            }
            event = parser.next()
        }
        return result
    }

    private fun localName(name: String?): String = name.orEmpty().substringAfter(':')

    private fun readText(parser: XmlPullParser): String =
        runCatching { parser.nextText().trim() }.getOrDefault("")

    private fun parseHttpDate(value: String): Long {
        if (value.isBlank()) return 0L
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        formats.forEach { pattern ->
            val time = runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(value)?.time
            }.getOrNull()
            if (time != null) return time
        }
        return 0L
    }
}
