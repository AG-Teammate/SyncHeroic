package app.syncheroic

import android.content.Context
import app.syncheroic.data.CredentialVault
import app.syncheroic.data.LocalStateStore
import app.syncheroic.health.HealthConnectGateway
import app.syncheroic.health.ExerciseMapLoader
import app.syncheroic.network.EndpointConfigLoader
import app.syncheroic.network.TrainHeroicClient
import app.syncheroic.network.RemoteConfigUpdater
import app.syncheroic.sync.SyncCoordinator
import app.syncheroic.sync.SyncScheduler
import app.syncheroic.core.RecordPlanner

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val credentialVault = CredentialVault(appContext)
    val stateStore = LocalStateStore(appContext)
    val endpoints = EndpointConfigLoader.loadBundled()
    val trainHeroicClient = TrainHeroicClient(endpoints, credentialVault)
    val healthConnectGateway = HealthConnectGateway(appContext)
    val syncCoordinator = SyncCoordinator(
        trainHeroicClient,
        healthConnectGateway,
        stateStore,
        planner = RecordPlanner(ExerciseMapLoader.loadBundled()),
        remoteConfigUpdater = RemoteConfigUpdater(),
    )

    fun configureFrequentSync(enabled: Boolean) = SyncScheduler.configureFrequent(appContext, enabled)
}
