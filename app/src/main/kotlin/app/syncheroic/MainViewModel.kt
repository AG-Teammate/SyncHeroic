package app.syncheroic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.syncheroic.core.MatchedSessionBehavior
import app.syncheroic.core.SyncSettings
import app.syncheroic.core.WeightUnit
import app.syncheroic.data.LocalStateStore
import app.syncheroic.data.FrequentSyncSettings
import app.syncheroic.network.TrainHeroicException
import app.syncheroic.sync.SyncPreview
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MainUiState(
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val summary: LocalStateStore.StateSummary? = null,
    val preview: SyncPreview? = null,
    val settings: SyncSettings? = null,
    val remoteConfigEnabled: Boolean = false,
    val frequentSync: FrequentSyncSettings = FrequentSyncSettings(),
)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState(signedIn = container.credentialVault.load() != null))
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    init { refresh() }

    fun signIn(email: String, password: String) = launchOperation("Connection successful") {
        container.trainHeroicClient.testAndSave(email, password)
        mutate { copy(signedIn = true) }
    }

    fun preview(days: Long = 30) = launchOperation("Preview ready") {
        val end = LocalDate.now()
        val result = container.syncCoordinator.preview(end.minusDays(days - 1), end)
        mutate { copy(preview = result) }
    }

    fun previewFullHistory() = launchOperation("Full-history preview ready") {
        val result = container.syncCoordinator.preview(LocalDate.of(2000, 1, 1), LocalDate.now())
        mutate { copy(preview = result) }
    }

    fun importPreview() = launchOperation("Sync complete") {
        val current = state.value.preview ?: return@launchOperation
        container.syncCoordinator.apply(current)
        refreshData()
    }

    fun deleteRecords() = launchOperation("SyncHeroic records deleted") {
        val count = container.syncCoordinator.deleteAll()
        container.stateStore.clear()
        container.configureFrequentSync(false)
        mutate { copy(preview = null, message = "$count records deleted") }
        refreshData()
    }

    fun signOutAndWipe() = launchOperation("Local data erased") {
        container.trainHeroicClient.signOut()
        container.stateStore.clear()
        container.configureFrequentSync(false)
        mutate { MainUiState(signedIn = false, message = "Local data erased") }
    }

    fun saveSettings(
        start: LocalTime,
        duration: Int,
        grace: Int,
        behavior: MatchedSessionBehavior,
        segments: Boolean,
        notesCap: Int,
        unit: WeightUnit,
        remoteConfig: Boolean,
        frequentSyncEnabled: Boolean,
        frequentSyncStart: LocalTime,
        frequentSyncEnd: LocalTime,
    ) = launchOperation("Settings saved") {
        container.stateStore.updateSettings(start, duration, grace, behavior, segments, notesCap, unit)
        container.stateStore.setRemoteConfig(remoteConfig)
        container.stateStore.setFrequentSync(frequentSyncEnabled, frequentSyncStart, frequentSyncEnd)
        container.configureFrequentSync(frequentSyncEnabled)
        refreshData()
    }

    fun refresh() { viewModelScope.launch { refreshData() } }
    fun clearMessage() { mutate { copy(message = null) } }

    private suspend fun refreshData() {
        val summary = container.stateStore.summary()
        val settings = container.stateStore.settings.first()
        val remoteConfig = container.stateStore.remoteConfigEnabled()
        val frequentSync = container.stateStore.frequentSyncSettings()
        mutate { copy(summary = summary, settings = settings, remoteConfigEnabled = remoteConfig, frequentSync = frequentSync) }
    }

    private fun launchOperation(success: String, operation: suspend () -> Unit) {
        viewModelScope.launch {
            mutate { copy(busy = true, message = null) }
            runCatching { operation() }
                .onSuccess { mutate { copy(busy = false, message = message ?: success) } }
                .onFailure { error ->
                    val safe = when (error) {
                        is TrainHeroicException -> error.message
                        else -> "The operation could not be completed"
                    }
                    mutate { copy(busy = false, message = safe) }
                }
        }
    }

    private fun mutate(block: MainUiState.() -> MainUiState) { mutableState.value = mutableState.value.block() }
}
