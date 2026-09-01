package app.syncheroic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.syncheroic.ui.PrivacyPolicy
import app.syncheroic.ui.SyncHeroicTheme

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SyncHeroicTheme { PrivacyPolicy(onBack = ::finish) } }
    }
}

