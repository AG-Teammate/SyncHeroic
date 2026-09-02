package app.syncheroic

import android.app.Application
import app.syncheroic.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SyncHeroicApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.scheduleDaily(this)
        applicationScope.launch {
            SyncScheduler.configureFrequent(this@SyncHeroicApplication, container.stateStore.frequentSyncSettings().enabled)
        }
    }
}
