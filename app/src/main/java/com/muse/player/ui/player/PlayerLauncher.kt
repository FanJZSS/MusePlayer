package com.muse.player.ui.player

import android.content.Context
import android.content.Intent
import com.muse.player.data.PlayEntry

object PlayerLauncher {

    fun open(context: Context, entries: List<PlayEntry>, index: Int, positionMs: Long = 0L) {
        if (entries.isEmpty()) return
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putParcelableArrayListExtra(PlayerActivity.EXTRA_ENTRIES, ArrayList(entries))
            putExtra(PlayerActivity.EXTRA_INDEX, index)
            putExtra(PlayerActivity.EXTRA_POSITION, positionMs)
        }
        context.startActivity(intent)
    }
}
