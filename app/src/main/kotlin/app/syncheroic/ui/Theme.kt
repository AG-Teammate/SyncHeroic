package app.syncheroic.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF275D55),
    onPrimary = Color.White,
    secondary = Color(0xFF4E635E),
    tertiary = Color(0xFF4D607B),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF94D1C6),
    secondary = Color(0xFFB5CCC5),
    tertiary = Color(0xFFB5C8E8),
)

@Composable
fun SyncHeroicTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = scheme, content = content)
}

