package dev.taracore.app.ui

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

/** The two brand colours. See docs/BRAND.md. */
val TaraIndigo = Color(0xFF1B1F3B)
val TaraGold = Color(0xFFF5C542)
private val TaraIndigoLight = Color(0xFF3A4173)
private val TaraGoldDeep = Color(0xFFB8901F)

private val DarkColors = darkColorScheme(
    primary = TaraGold,
    onPrimary = TaraIndigo,
    primaryContainer = TaraIndigoLight,
    onPrimaryContainer = TaraGold,
    secondary = TaraIndigoLight,
    background = TaraIndigo,
    onBackground = Color(0xFFE8E9F2),
    surface = Color(0xFF23274A),
    onSurface = Color(0xFFE8E9F2),
)

private val LightColors = lightColorScheme(
    primary = TaraIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE0F5),
    onPrimaryContainer = TaraIndigo,
    secondary = TaraGoldDeep,
    background = Color(0xFFFAFAFC),
    surface = Color.White,
)

@Composable
fun TaraCoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Material You where the platform offers it, brand colours everywhere else. A
    // system component should look like it belongs to the device it runs on.
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
