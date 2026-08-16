package dev.kaiharimoto.masterkey.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Receives the outcome of a [PackageInstaller] session.
 *
 * The important branch is `STATUS_PENDING_USER_ACTION`: the system hands back an
 * intent that must be launched to show the confirmation dialog. Ignoring it makes
 * the install appear to hang forever with no error and no prompt, which is the
 * most common way self-updaters break.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                    emit(InstallOutcome.AwaitingConfirmation)
                } else {
                    emit(InstallOutcome.Failed("The system didn't return a confirmation prompt."))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> emit(InstallOutcome.Success)

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install failed: status=$status message=$message")
                emit(InstallOutcome.Failed(explain(status, message)))
            }
        }
    }

    private fun explain(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "The system blocked the install."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            // Almost always a signing mismatch, which for this app would mean the
            // release key changed. Say so plainly rather than showing a code.
            "This update was signed with a different key than the installed app, " +
                "so Android won't install it over the top."

        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This build isn't compatible with your device."
        PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded file wasn't a valid APK."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free storage to install the update."
        else -> message ?: "The update couldn't be installed."
    }

    private fun emit(outcome: InstallOutcome) {
        _outcomes.tryEmit(outcome)
    }

    companion object {
        private const val TAG = "MasterKeyUpdate"
        const val ACTION_INSTALL_RESULT = "dev.kaiharimoto.masterkey.INSTALL_RESULT"

        private val _outcomes = MutableSharedFlow<InstallOutcome>(
            replay = 0,
            extraBufferCapacity = 4,
        )
        val outcomes: SharedFlow<InstallOutcome> = _outcomes
    }
}

sealed interface InstallOutcome {
    data object AwaitingConfirmation : InstallOutcome
    data object Success : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome
}
