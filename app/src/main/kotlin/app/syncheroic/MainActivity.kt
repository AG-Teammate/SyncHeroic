package app.syncheroic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModelProvider
import app.syncheroic.ui.SyncHeroicApp
import app.syncheroic.sync.SyncScheduler

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        SyncScheduler.enqueueForeground(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SyncHeroicApplication).container
        val model = ViewModelProvider(this, factory { MainViewModel(container) })[MainViewModel::class.java]
        val permissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            model.refresh()
        }
        setContent {
            SyncHeroicApp(
                model = model,
                healthAvailable = container.healthConnectGateway.availability,
                requestPermissions = { permissionLauncher.launch(container.healthConnectGateway.permissionsToRequest()) },
            )
        }
    }
}

private inline fun <reified T : androidx.lifecycle.ViewModel> factory(crossinline create: () -> T) =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : androidx.lifecycle.ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
