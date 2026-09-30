package com.example.keymessage

import android.app.Application
import com.example.keymessage.storage.room.database.KeyMessageDatabase
import com.example.keymessage.storage.room.impl.RoomDuplicateStore
import com.example.keymessage.storage.room.impl.RoomMessageStore
import com.example.keymessage.storage.room.impl.RoomOfflineQueue

class KeyMessageApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.core.start()
    }

    override fun onTerminate() {
        container.core.stop()
        super.onTerminate()
    }
}
