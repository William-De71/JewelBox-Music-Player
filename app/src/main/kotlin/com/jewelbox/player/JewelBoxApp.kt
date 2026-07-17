package com.jewelbox.player

import android.app.Application
import com.jewelbox.player.data.AlbumRepository
import com.jewelbox.player.data.ServerPrefs
import com.jewelbox.player.playback.PlayerConnection

/**
 * Application-scoped container. Manual DI is enough at this size — one repository
 * and one prefs store, created once and shared. ViewModels reach these through
 * [ServiceLocator] rather than taking constructor dependencies, keeping the
 * default ViewModel factory usable.
 */
class JewelBoxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Binds the MediaController to the playback service for the whole app lifetime.
        PlayerConnection.init(this)
    }
}

object ServiceLocator {
    lateinit var serverPrefs: ServerPrefs
        private set
    lateinit var albumRepository: AlbumRepository
        private set

    fun init(app: Application) {
        serverPrefs = ServerPrefs(app.applicationContext)
        albumRepository = AlbumRepository(serverPrefs)
    }
}
