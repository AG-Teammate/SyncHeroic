package app.syncheroic

import android.app.Application
import app.syncheroic.sync.SyncScheduler

class SyncHeroicApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedule(this)
    }
}

