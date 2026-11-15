package com.muse.player.playback.network

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.muse.player.data.network.NetworkClients
import com.muse.player.data.network.NetworkRepository
import com.muse.player.data.network.NetworkServer
import com.muse.player.data.network.ServerType
import jcifs.SmbConstants
import jcifs.smb.SmbException
import jcifs.smb.SmbRandomAccessFile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 支持局域网协议（SMB / FTP / WebDAV）的 Media3 数据源。
 *
 * 自定义 scheme 只携带服务器 id 与相对路径，凭据从本地配置读取；
 * 三种协议都支持随机定位，因此远程视频可以正常拖动进度。
 */
@OptIn(UnstableApi::class)
class MuseDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    private var smbFile: SmbRandomAccessFile? = null
    private var ftpClient: FTPClient? = null
    private var ftpStream: InputStream? = null
    private var httpDataSource: DefaultHttpDataSource? = null
    private var fallback: DataSource? = null

    // SMB/FTP 预读缓冲：播放器每次只要求 64KB，若不预读每个小块都要一次网络往返，
    // 高码率视频就会周期性喂不饱而卡顿。这里一次读 1MB，摊薄往返开销。
    private val readAheadBuffer = ByteArray(READ_AHEAD_SIZE)
    private var readAheadPos = 0
    private var readAheadLen = 0

    override fun open(dataSpec: DataSpec): Long {
        closeQuietly()
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val resolved = NetworkRepository.resolve(dataSpec.uri)
        if (resolved == null) {
            val delegate = DefaultDataSource.Factory(context, httpDataSourceFactory()).createDataSource()
            fallback = delegate
            val length = delegate.open(dataSpec)
            transferStarted(dataSpec)
            return length
        }

        val (server, relativePath) = resolved
        try {
            when (server.type) {
                ServerType.SMB -> openSmb(server, relativePath, dataSpec)
                ServerType.FTP -> openFtp(server, relativePath, dataSpec)
                ServerType.WEBDAV -> openWebDav(server, relativePath, dataSpec)
            }
        } catch (e: IOException) {
            closeQuietly()
            throw e
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun openSmb(server: NetworkServer, relativePath: String, dataSpec: DataSpec) {
        val context = NetworkClients.smbContext(server)
        val url = NetworkRepository.smbUrl(server, relativePath, directory = false)
        val file = try {
            SmbRandomAccessFile(url, "r", SmbConstants.DEFAULT_SHARING, context)
        } catch (e: SmbException) {
            throw NetworkClients.smbError(server, e)
        }
        smbFile = file
        readAheadPos = 0
        readAheadLen = 0
        val total = file.length()
        val position = dataSpec.position.coerceAtLeast(0L)
        file.seek(position)
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total > 0 -> (total - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    private fun openFtp(server: NetworkServer, relativePath: String, dataSpec: DataSpec) {
        val client = FTPClient()
        ftpClient = client
        client.connect(server.host, server.port)
        client.setAutodetectUTF8(true)
        val user = server.username.ifBlank { "anonymous" }
        val pass = if (server.username.isBlank()) "anonymous@" else server.password
        if (!client.login(user, pass)) {
            throw IOException("FTP 登录失败，请检查账号密码")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)

        val position = dataSpec.position.coerceAtLeast(0L)
        if (position > 0) {
            client.setRestartOffset(position)
        }
        val input = client.retrieveFileStream(relativePath)
            ?: throw IOException("FTP 无法打开文件：$relativePath")
        ftpStream = BufferedInputStream(input, READ_AHEAD_SIZE)
        // 不再额外发送 LIST 查询文件大小：每次拖动都会多一个网络往返，明显拖慢定位
        bytesRemaining = dataSpec.length
    }

    private fun openWebDav(server: NetworkServer, relativePath: String, dataSpec: DataSpec) {
        val url = NetworkRepository.webDavUrl(server, relativePath, directory = false)
        val factory = httpDataSourceFactory()
        NetworkRepository.basicAuthHeader(server)?.let {
            factory.setDefaultRequestProperties(mapOf("Authorization" to it))
        }
        val http = factory.createDataSource()
        httpDataSource = http
        val httpSpec = dataSpec.buildUpon()
            .setUri(Uri.parse(url))
            .setKey(url)
            .build()
        val length = http.open(httpSpec)
        bytesRemaining = if (length == C.LENGTH_UNSET.toLong()) {
            C.LENGTH_UNSET.toLong()
        } else {
            length
        }
    }

    /** 从预读缓冲返回数据，缓冲耗尽时再向服务器发起一次大块读取 */
    private fun readSmbBuffered(target: ByteArray, offset: Int, length: Int): Int {
        val file = smbFile ?: return C.RESULT_END_OF_INPUT
        if (readAheadPos >= readAheadLen) {
            val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                READ_AHEAD_SIZE
            } else {
                minOf(READ_AHEAD_SIZE.toLong(), bytesRemaining).toInt()
            }
            if (want <= 0) return C.RESULT_END_OF_INPUT
            val got = file.read(readAheadBuffer, 0, want)
            if (got <= 0) return C.RESULT_END_OF_INPUT
            readAheadPos = 0
            readAheadLen = got
        }
        val count = minOf(readAheadLen - readAheadPos, length)
        System.arraycopy(readAheadBuffer, readAheadPos, target, offset, count)
        readAheadPos += count
        return count
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val maxRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        if (maxRead <= 0) return C.RESULT_END_OF_INPUT

        val count = when {
            smbFile != null -> readSmbBuffered(buffer, offset, maxRead)
            ftpStream != null -> ftpStream!!.read(buffer, offset, maxRead)
            httpDataSource != null -> httpDataSource!!.read(buffer, offset, maxRead)
            fallback != null -> fallback!!.read(buffer, offset, maxRead)
            else -> C.RESULT_END_OF_INPUT
        }
        if (count == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        if (count > 0) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= count
            }
            bytesTransferred(count)
        }
        return count
    }

    override fun close() {
        closeQuietly()
        transferEnded()
    }

    private fun closeQuietly() {
        runCatching { smbFile?.close() }
        smbFile = null
        readAheadPos = 0
        readAheadLen = 0

        runCatching { ftpStream?.close() }
        ftpStream = null
        ftpClient?.let { client ->
            runCatching { client.completePendingCommand() }
            runCatching { if (client.isConnected) client.logout() }
            runCatching { if (client.isConnected) client.disconnect() }
        }
        ftpClient = null

        runCatching { httpDataSource?.close() }
        httpDataSource = null
        runCatching { fallback?.close() }
        fallback = null

        bytesRemaining = C.LENGTH_UNSET.toLong()
    }

    override fun getUri(): Uri? = uri

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = MuseDataSource(context)
    }

    companion object {
        private const val READ_AHEAD_SIZE = 1024 * 1024

        /**
         * 统一的 User-Agent。ExoPlayer 默认 UA 会被部分流媒体源站拒绝（返回 403 或网页），
         * 表现为「无法识别容器格式」或「IO 错误」，因此统一伪装成常见移动端浏览器。
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/119.0.0.0 Mobile Safari/537.36"

        fun httpDataSourceFactory(): DefaultHttpDataSource.Factory =
            DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)
    }
}
