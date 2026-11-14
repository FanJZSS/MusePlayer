package com.muse.player

import android.app.Application
import com.muse.player.data.HistoryRepository
import com.muse.player.data.SettingsRepository

class MuseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository.init(this)
        HistoryRepository.init(this)
    }
}
