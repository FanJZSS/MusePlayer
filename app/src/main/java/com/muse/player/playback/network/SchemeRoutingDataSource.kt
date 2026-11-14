package com.muse.player.playback.network

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 按 URI scheme 分流：网络地址（http/https/muse+*）走带磁盘缓存的 DataSource，
 * 本地文件直接读取，避免把本地视频重复写入缓存目录。
 */
@OptIn(UnstableApi::class)
class SchemeRoutingDataSourceFactory(
    private val directFactory: DataSource.Factory,
    private val cacheFactory: DataSource.Factory
) : DataSource.Factory {

    override fun createDataSource(): DataSource = RoutingDataSource(directFactory, cacheFactory)

    private class RoutingDataSource(
        private val directFactory: DataSource.Factory,
        private val cacheFactory: DataSource.Factory
    ) : DataSource {

        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            val cacheable = scheme != null &&
                (scheme.startsWith("muse+") || scheme == "http" || scheme == "https")
            val source = if (cacheable) {
                cacheFactory.createDataSource()
            } else {
                directFactory.createDataSource()
            }
            listeners.forEach { source.addTransferListener(it) }
            delegate = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = delegate?.uri

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }
}
