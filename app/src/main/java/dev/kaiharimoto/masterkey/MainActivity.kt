package dev.kaiharimoto.masterkey

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.kaiharimoto.masterkey.ui.library.LibraryScreen
import dev.kaiharimoto.masterkey.ui.player.KeyDispatcher
import dev.kaiharimoto.masterkey.ui.player.LocalKeyDispatcher
import dev.kaiharimoto.masterkey.ui.player.PlayerScreen
import dev.kaiharimoto.masterkey.ui.settings.SettingsScreen
import dev.kaiharimoto.masterkey.ui.theme.MasterKeyTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Hardware keys, routed before the window dispatches them.
     *
     * The score pane is a WebView, and a focused WebView eats space and the
     * arrow keys before Compose sees them. Intercepting here is the only place
     * that is reliably ahead of it.
     */
    private val keyDispatcher = KeyDispatcher()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keyDispatcher.dispatch(event, currentFocus) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mandatory from targetSdk 35 onwards — there is no opt-out, so insets are
        // consumed explicitly by each screen rather than papered over.
        enableEdgeToEdge()
        askForNotificationPermission()

        setContent {
            MasterKeyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CompositionLocalProvider(LocalKeyDispatcher provides keyDispatcher) {
                        MasterKeyNavHost()
                    }
                }
            }
        }
    }

    /**
     * The playback foreground service needs a notification to exist at all. On
     * API 33+ that notification is suppressed without this permission, so the
     * service would run invisibly and the user would have no way to get back to
     * a piece that is still playing.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private object Routes {
    const val LIBRARY = "library"
    const val PLAYER = "player/{songId}"
    const val SETTINGS = "settings"

    fun player(songId: String) = "player/$songId"
}

@Composable
private fun MasterKeyNavHost() {
    val navController = rememberNavController()

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Routes.LIBRARY) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenSong = { navController.navigate(Routes.player(it)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.PLAYER) { entry ->
                val songId = entry.arguments?.getString("songId").orEmpty()
                PlayerScreen(
                    songId = songId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
