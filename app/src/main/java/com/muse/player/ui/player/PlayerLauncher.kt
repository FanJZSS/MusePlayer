package com.muse.player.ui.player

import android.content.Context
import android.content.Intent
import com.muse.player.data.PlayEntry
import com.muse.player.playback.PlaybackService

object PlayerLauncher {

    fun open(context: Context, entries: List<PlayEntry>, index: Int, positionMs: Long = 0L) {
        if (entries.isEmpty()) return
        // 提前拉起播放服务并创建会话，省去 Activity 启动后才建立连接的时间
        runCatching {
            context.startService(Intent(context, PlaybackService::class.java))
        }
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putParcelableArrayListExtra(PlayerActivity.EXTRA_ENTRIES, ArrayList(entries))
            putExtra(PlayerActivity.EXTRA_INDEX, index)
            putExtra(PlayerActivity.EXTRA_POSITION, positionMs)
        }
        context.startActivity(intent)
    }
}
