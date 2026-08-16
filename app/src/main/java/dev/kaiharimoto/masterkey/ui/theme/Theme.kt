package dev.kaiharimoto.masterkey.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The shared colour language, used identically by the falling-note highway and
 * the sheet-music pane. A note that is teal in one is teal in the other and teal
 * on the key it lands on — that consistency is the whole point, because it is
 * what links "this shape on the staff" to "this key under my finger".
 */
object HandColors {
    /** Right hand. */
    val amber = Color(0xFFFFB020)
    val amberBright = Color(0xFFFFD37A)
    val amberDark = Color(0xFFB87400)

    /** Left hand. */
    val teal = Color(0xFF2ED3C6)
    val tealBright = Color(0xFF8CF0E8)
    val tealDark = Color(0xFF14807A)
}

/** Surfaces for the highway, which is always dark regardless of app theme. */
object HighwayColors {
    val background = Color(0xFF0D0F14)
    val laneWhite = Color(0xFF161A22)
    val laneBlack = Color(0xFF0A0C10)
    val barLine = Color(0xFF3A4152)
    val beatLine = Color(0xFF232935)
    val hitLine = Color(0xFF6F7A90)
    val keyWhite = Color(0xFFF2F4F8)
    val keyBlack = Color(0xFF1B1F27)
    val keyLabel = Color(0xFF7A8496)
    val middleCMarker = Color(0xFFE0574B)
}

private val DarkScheme = darkColorScheme(
    primary = HandColors.amber,
    onPrimary = Color(0xFF241800),
    secondary = HandColors.teal,
    onSecondary = Color(0xFF00201E),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE3E6EC),
    surface = Color(0xFF14161C),
    onSurface = Color(0xFFE3E6EC),
    surfaceVariant = Color(0xFF1E222B),
    onSurfaceVariant = Color(0xFFB6BDCA),
    outline = Color(0xFF3A4152),
    error = Color(0xFFE0574B),
)

private val LightScheme = lightColorScheme(
    primary = HandColors.amberDark,
    secondary = HandColors.tealDark,
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
)

/** Exposes whether the surrounding UI is dark, for the score WebView's CSS. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

@Composable
fun MasterKeyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}
