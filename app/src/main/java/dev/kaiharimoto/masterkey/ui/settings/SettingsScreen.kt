package dev.kaiharimoto.masterkey.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kaiharimoto.masterkey.MasterKeyApp
import dev.kaiharimoto.masterkey.R
import dev.kaiharimoto.masterkey.update.DownloadProgress
import dev.kaiharimoto.masterkey.update.InstallOutcome
import dev.kaiharimoto.masterkey.update.InstallResultReceiver
import dev.kaiharimoto.masterkey.update.ReleaseInfo
import dev.kaiharimoto.masterkey.update.UpdateRepository
import dev.kaiharimoto.masterkey.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class UpdateUiState(
    val checking: Boolean = false,
    val message: String? = null,
    val available: ReleaseInfo? = null,
    val downloadFraction: Float? = null,
    val downloadedApk: File? = null,
    val needsInstallPermission: Boolean = false,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: UpdateRepository = (application as MasterKeyApp).graph.updateRepository

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val currentVersion: String get() = repository.currentVersionName

    init {
        viewModelScope.launch {
            InstallResultReceiver.outcomes.collect { outcome ->
                _state.value = when (outcome) {
                    is InstallOutcome.AwaitingConfirmation ->
                        _state.value.copy(message = "Confirm the install when Android asks.")

                    is InstallOutcome.Success ->
                        _state.value.copy(message = "Updated. Reopen the app to use the new version.")

                    is InstallOutcome.Failed ->
                        _state.value.copy(message = outcome.reason)
                }
            }
        }
        // Check on launch, but never download without being asked.
        check()
    }

    fun check() {
        viewModelScope.launch {
            _state.value = _state.value.copy(checking = true, message = null)
            _state.value = when (val result = repository.check()) {
                is UpdateStatus.UpToDate -> UpdateUiState(message = "You're on the latest version.")
                is UpdateStatus.Available -> UpdateUiState(available = result.release)
                is UpdateStatus.Failed -> UpdateUiState(message = result.reason)
            }
        }
    }

    fun download() {
        val release = _state.value.available ?: return
        viewModelScope.launch {
            repository.download(release).collect { progress ->
                _state.value = when (progress) {
                    is DownloadProgress.Downloading ->
                        _state.value.copy(downloadFraction = progress.fraction)

                    is DownloadProgress.Complete ->
                        _state.value.copy(downloadFraction = null, downloadedApk = progress.file)

                    is DownloadProgress.Failed ->
                        _state.value.copy(downloadFraction = null, message = progress.reason)
                }
            }
            _state.value.downloadedApk?.let { install(it) }
        }
    }

    private fun install(apk: File) {
        if (!repository.canInstall()) {
            _state.value = _state.value.copy(needsInstallPermission = true)
            return
        }
        viewModelScope.launch {
            repository.install(apk).onFailure {
                _state.value = _state.value.copy(
                    message = it.message ?: "The update couldn't be installed.",
                )
            }
        }
    }

    fun installPermissionIntent() = repository.installPermissionIntent()

    fun retryInstall() {
        _state.value = _state.value.copy(needsInstallPermission = false)
        _state.value.downloadedApk?.let { install(it) }
    }

    fun dismiss() {
        _state.value = _state.value.copy(available = null, message = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: UpdateViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Master Key", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Version ${viewModel.currentVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))

                    when {
                        state.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(18.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                        }

                        state.needsInstallPermission -> Column {
                            Text(
                                "Android needs your permission to let Master Key install " +
                                    "its own updates. You only have to do this once.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                context.startActivity(viewModel.installPermissionIntent())
                            }) { Text("Open settings") }
                            TextButton(onClick = viewModel::retryInstall) {
                                Text("I've allowed it — install now")
                            }
                        }

                        state.downloadFraction != null -> Column {
                            Text("Downloading…", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { state.downloadFraction ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        state.available != null -> UpdateAvailable(
                            release = state.available!!,
                            onUpdate = viewModel::download,
                            onLater = viewModel::dismiss,
                        )

                        else -> Column {
                            state.message?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(8.dp))
                            }
                            Button(onClick = viewModel::check) {
                                Text(androidx.compose.ui.res.stringResource(R.string.check_for_updates))
                            }
                        }
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Open source", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Notation engraving by Verovio (LGPL-3.0). Audio by TinySoundFont " +
                            "(MIT) and Oboe (Apache-2.0). MIDI parsing by ktmidi (MIT). " +
                            "Piano samples: FreePats Upright Piano KW (CC0).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailable(
    release: ReleaseInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    Column {
        Text(
            "Version ${release.versionName} is available",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (release.notes.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                release.notes.lines().take(8).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onUpdate) {
                Text(androidx.compose.ui.res.stringResource(R.string.update_now))
            }
            TextButton(onClick = onLater) {
                Text(androidx.compose.ui.res.stringResource(R.string.update_later))
            }
        }
    }
}
