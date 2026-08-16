package dev.kaiharimoto.masterkey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.kaiharimoto.masterkey.ui.library.LibraryScreen
import dev.kaiharimoto.masterkey.ui.player.PlayerScreen
import dev.kaiharimoto.masterkey.ui.settings.SettingsScreen
import dev.kaiharimoto.masterkey.ui.theme.MasterKeyTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mandatory from targetSdk 35 onwards — there is no opt-out, so insets are
        // consumed explicitly by each screen rather than papered over.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MasterKeyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MasterKeyNavHost()
                }
            }
        }
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
