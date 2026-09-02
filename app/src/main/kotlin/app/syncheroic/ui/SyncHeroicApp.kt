package app.syncheroic.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import app.syncheroic.MainViewModel
import app.syncheroic.core.MatchedSessionBehavior
import app.syncheroic.core.PlanAction
import app.syncheroic.core.WeightUnit
import app.syncheroic.data.FrequentSyncSettings
import java.time.LocalTime

private enum class Destination(val label: String) { HOME("Home"), SESSIONS("Sessions"), SETTINGS("Settings"), PRIVACY("Privacy") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHeroicApp(model: MainViewModel, healthAvailable: Int, requestPermissions: () -> Unit) {
    SyncHeroicTheme {
        val state by model.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        var destination by remember { mutableStateOf(Destination.HOME) }
        state.message?.let { message ->
            LaunchedEffect(message) { snackbar.showSnackbar(message); model.clearMessage() }
        }
        if (!state.signedIn) {
            SignInScreen(state.busy, model::signIn)
            return@SyncHeroicTheme
        }
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (destination == Destination.HOME) "SyncHeroic" else destination.label) }) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Text(item.label.take(1)) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (destination) {
                    Destination.HOME -> HomeScreen(state.busy, state.summary?.lastSuccess?.toString(), state.summary?.driftCount ?: 0, state.preview, healthAvailable, requestPermissions, { model.preview() }, model::previewFullHistory, model::importPreview)
                    Destination.SESSIONS -> SessionsScreen(state.preview)
                    Destination.SETTINGS -> SettingsScreen(state.settings, state.remoteConfigEnabled, state.frequentSync, model::saveSettings)
                    Destination.PRIVACY -> DataPrivacyScreen(state.preview?.drift, model::deleteRecords, model::signOutAndWipe)
                }
                if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun SignInScreen(busy: Boolean, signIn: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SyncHeroic", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("Your training, readable in Health Connect.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("TrainHeroic email") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Spacer(Modifier.height(16.dp))
        Button({ signIn(email, password) }, enabled = !busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Test connection and sign in") }
        Spacer(Modifier.height(16.dp))
        Text("Credentials are encrypted on this device and sent only to TrainHeroic. No telemetry or SyncHeroic server.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Unofficial. Not affiliated with or endorsed by TrainHeroic.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HomeScreen(
    busy: Boolean,
    lastSync: String?,
    drift: Int,
    preview: app.syncheroic.sync.SyncPreview?,
    availability: Int,
    requestPermissions: () -> Unit,
    previewSync: () -> Unit,
    previewFullHistory: () -> Unit,
    import: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (availability != HealthConnectClient.SDK_AVAILABLE) item {
            NoticeCard("Health Connect needs to be installed or updated before records can be written.")
        }
        if (drift > 0) item { NoticeCard("TrainHeroic response drift detected: $drift signals. Review Data and privacy before reporting it.") }
        item {
            Text("Last successful sync", style = MaterialTheme.typography.labelLarge)
            Text(lastSync ?: "Never", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(requestPermissions, enabled = availability == HealthConnectClient.SDK_AVAILABLE) { Text("Health permissions") }
                Button(previewSync, enabled = !busy) { Text("Preview 30 days") }
            }
        }
        item { TextButton(previewFullHistory, enabled = !busy) { Text("Preview full history") } }
        preview?.let { result ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Import preview", style = MaterialTheme.typography.titleLarge)
                        Text("${result.inserts} insert · ${result.updates} update · ${result.held} awaiting · ${result.skipped} skipped · ${result.unchanged} unchanged")
                        Spacer(Modifier.height(12.dp))
                        Button(import, enabled = result.inserts + result.updates > 0) { Text("Write previewed records") }
                    }
                }
            }
        }
    }
}

@Composable private fun NoticeCard(text: String) {
    Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun SessionsScreen(preview: app.syncheroic.sync.SyncPreview?) {
    val actions = preview?.actions.orEmpty().sortedByDescending { action ->
        when (action) {
            is PlanAction.Insert -> action.record.date
            is PlanAction.Update -> action.record.date
            is PlanAction.Unchanged -> action.record.date
            else -> null
        }
    }
    if (actions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Run a preview to inspect sessions.") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(actions, key = { it.workoutId }) { action ->
            val record = when (action) {
                is PlanAction.Insert -> action.record
                is PlanAction.Update -> action.record
                is PlanAction.Unchanged -> action.record
                else -> null
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(record?.title ?: action.workoutId, style = MaterialTheme.typography.titleMedium)
                Text(record?.let { "${it.date} · ${it.timeSource.name.lowercase().replace('_', ' ')}" } ?: action.javaClass.simpleName, style = MaterialTheme.typography.bodySmall)
                if (record != null) Text(record.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: app.syncheroic.core.SyncSettings?,
    remoteConfigEnabled: Boolean,
    frequentSync: FrequentSyncSettings,
    save: (LocalTime, Int, Int, MatchedSessionBehavior, Boolean, Int, WeightUnit, Boolean, Boolean, LocalTime, LocalTime) -> Unit,
) {
    settings ?: return
    var start by remember(settings) { mutableStateOf(settings.defaultStartTime.toString()) }
    var duration by remember(settings) { mutableStateOf(settings.defaultDuration.toMinutes().toString()) }
    var grace by remember(settings) { mutableStateOf(settings.matchGracePeriod.toHours().toString()) }
    var segments by remember(settings) { mutableStateOf(settings.segmentsEnabled) }
    var behavior by remember(settings) { mutableStateOf(settings.matchedSessionBehavior) }
    var unit by remember(settings) { mutableStateOf(settings.displayWeightUnit) }
    var remoteConfig by remember(remoteConfigEnabled) { mutableStateOf(remoteConfigEnabled) }
    var frequentEnabled by remember(frequentSync) { mutableStateOf(frequentSync.enabled) }
    var frequentStart by remember(frequentSync) { mutableStateOf(frequentSync.start.toString()) }
    var frequentEnd by remember(frequentSync) { mutableStateOf(frequentSync.end.toString()) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(start, { start = it }, label = { Text("Default start time (HH:mm)") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(duration, { duration = it.filter(Char::isDigit) }, label = { Text("Default duration (minutes)") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(grace, { grace = it.filter(Char::isDigit) }, label = { Text("Match grace period (hours)") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Text("When a wearable session matches", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MatchedSessionBehavior.entries.forEach { value -> TextButton({ behavior = value }) { Text(if (behavior == value) "✓ ${value.label}" else value.label) } }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(segments, { segments = it }); Text("Include supported exercise segments") } }
        item {
            Text("Display weight", style = MaterialTheme.typography.labelLarge)
            Row { WeightUnit.entries.forEach { value -> TextButton({ unit = value }) { Text(if (unit == value) "✓ ${value.name.lowercase()}" else value.name.lowercase()) } } }
        }
        item {
            Text("Frequent background sync", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(frequentEnabled, { frequentEnabled = it })
                Column {
                    Text("Sync every 15 minutes during workout window")
                    Text("Best effort; Android may delay background work.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    frequentStart,
                    { frequentStart = it },
                    label = { Text("Window start") },
                    enabled = frequentEnabled,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    frequentEnd,
                    { frequentEnd = it },
                    label = { Text("Window end") },
                    enabled = frequentEnabled,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(remoteConfig, { remoteConfig = it })
                Column {
                    Text("Fetch public mapping updates")
                    Text("Opt-in. Contacts the SyncHeroic GitHub repository.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            val parsedStart = runCatching { LocalTime.parse(start) }.getOrNull()
            val parsedFrequentStart = runCatching { LocalTime.parse(frequentStart) }.getOrNull()
            val parsedFrequentEnd = runCatching { LocalTime.parse(frequentEnd) }.getOrNull()
            val validFrequentWindow = parsedFrequentStart != null && parsedFrequentEnd != null &&
                parsedFrequentEnd.isAfter(parsedFrequentStart) &&
                java.time.Duration.between(parsedFrequentStart, parsedFrequentEnd) >= java.time.Duration.ofMinutes(15)
            Button(
                enabled = parsedStart != null && validFrequentWindow,
                onClick = {
                    if (parsedStart != null && parsedFrequentStart != null && parsedFrequentEnd != null) {
                        save(parsedStart, duration.toIntOrNull() ?: 60, grace.toIntOrNull() ?: 48, behavior, segments, settings.notesCap, unit, remoteConfig, frequentEnabled, parsedFrequentStart, parsedFrequentEnd)
                    }
                },
            ) { Text("Save settings") }
        }
    }
}

private val MatchedSessionBehavior.label: String get() = when (this) {
    MatchedSessionBehavior.ALIGN -> "Align"
    MatchedSessionBehavior.SYNTHESIZE_ANYWAY -> "Separate"
    MatchedSessionBehavior.SKIP -> "Skip"
}

@Composable
private fun DataPrivacyScreen(drift: app.syncheroic.core.DriftReport?, deleteRecords: () -> Unit, wipe: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PrivacyCopy() }
        item {
            Text("Schema report", style = MaterialTheme.typography.titleLarge)
            Text(drift?.let { "${it.total} signals · ${it.unknownPaths.size} unknown paths · ${it.unparsedPerformedValues.values.sum()} unparsed values" } ?: "Run a preview to generate a report.")
        }
        item {
            OutlinedButton(
                enabled = drift != null,
                onClick = {
                    val report = drift?.shapeOnlyText().orEmpty()
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "SyncHeroic shape-only report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }, "Share shape-only report"))
                },
            ) { Text("Share shape-only report") }
        }
        item { OutlinedButton({ confirmDelete = true }) { Text("Delete all records written by SyncHeroic") } }
        item { OutlinedButton({ confirmWipe = true }) { Text("Sign out and erase local data") } }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete SyncHeroic records?") },
        text = { Text("This removes only exercise sessions written by this application. Wearable records are untouched.") },
        confirmButton = { Button({ confirmDelete = false; deleteRecords() }) { Text("Delete records") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Erase local data?") },
        text = { Text("This signs out and deletes encrypted credentials, settings, and sync provenance. Health Connect records remain until separately deleted.") },
        confirmButton = { Button({ confirmWipe = false; wipe() }) { Text("Erase") } },
        dismissButton = { TextButton({ confirmWipe = false }) { Text("Cancel") } },
    )
}

private fun app.syncheroic.core.DriftReport.shapeOnlyText(): String = buildString {
    appendLine("SyncHeroic shape-only report")
    unknownPaths.forEach { (path, count) -> appendLine("unknown $path, n=$count") }
    unparsedPerformedValues.forEach { (path, count) -> appendLine("unparsed $path, n=$count") }
    unmappedExerciseNames.forEach { (_, count) -> appendLine("unmapped exercise name, n=$count") }
    unresolvedUnitSemantics.forEach { (_, count) -> appendLine("unresolved unit semantics, n=$count") }
}

@Composable
fun PrivacyPolicy(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("SyncHeroic privacy", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        PrivacyCopy()
        Spacer(Modifier.height(24.dp))
        Button(onBack) { Text("Close") }
    }
}

@Composable private fun PrivacyCopy() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SyncHeroic has no backend, account, telemetry, analytics, advertising, or crash reporter.")
        Text("Your credentials are encrypted by Android Keystore and sent only to TrainHeroic. Workout data moves from TrainHeroic to Health Connect on this phone.")
        Text("Exercise-session read access is used to avoid duplicates and align times with wearable records. SyncHeroic never edits or deletes another app’s data.")
        Text("The TrainHeroic API is undocumented and may change. SyncHeroic is unofficial and unaffiliated.")
    }
}
