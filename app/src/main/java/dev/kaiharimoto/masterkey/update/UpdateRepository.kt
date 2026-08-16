package dev.kaiharimoto.masterkey.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import dev.kaiharimoto.masterkey.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus
    data class Available(val release: ReleaseInfo) : UpdateStatus
    data class Failed(val reason: String) : UpdateStatus
}

sealed interface DownloadProgress {
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : DownloadProgress {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    data class Complete(val file: File) : DownloadProgress
    data class Failed(val reason: String) : DownloadProgress
}

/**
 * Checks GitHub Releases for a newer build, downloads it and hands it to the
 * system installer.
 *
 * A few decisions here are load-bearing:
 *
 *  - **Versions are compared as numbers, never as strings.** `"1.10.0"` sorts
 *    below `"1.9.0"` lexicographically, which would silently stop offering
 *    updates after version 1.9.
 *  - **The download URL always comes from the API response**, never constructed.
 *    GitHub redirects asset downloads to a signed URL, and hand-building the link
 *    breaks the moment their scheme changes.
 *  - **OkHttp into `cacheDir`, not `DownloadManager`.** DownloadManager is a
 *    system service and cannot write into the app sandbox; it is also now subject
 *    to Android 16 job quotas, which can stall a download.
 *  - **`PackageInstaller`, not `ACTION_VIEW` with a `FileProvider`.** The legacy
 *    intent path is deprecated and, worse, reports no reason when an install
 *    fails — leaving no way to tell the user what went wrong.
 */
class UpdateRepository(private val context: Context) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Asset URLs 302 to a signed host; following that is required.
            .followRedirects(true)
            .build()
    }

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    val currentVersionCode: Long
        get() = runCatching {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        }.getOrDefault(BuildConfig.VERSION_CODE.toLong())

    suspend fun check(): UpdateStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MasterKey/${BuildConfig.VERSION_NAME}")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 403 &&
                    response.header("X-RateLimit-Remaining") == "0"
                ) {
                    // Unauthenticated GitHub calls are limited per IP. Behind
                    // carrier-grade NAT this can trip through no fault of ours,
                    // and it is not worth showing the user an error for.
                    return@withContext UpdateStatus.UpToDate
                }
                if (response.code == 404) {
                    return@withContext UpdateStatus.Failed("No releases published yet.")
                }
                if (!response.isSuccessful) {
                    return@withContext UpdateStatus.Failed("GitHub returned ${response.code}.")
                }

                val body = response.body?.string()
                    ?: return@withContext UpdateStatus.Failed("Empty response from GitHub.")
                val release = parseRelease(JSONObject(body))
                    ?: return@withContext UpdateStatus.Failed("That release has no APK attached.")

                if (release.versionCode > currentVersionCode) {
                    UpdateStatus.Available(release)
                } else {
                    UpdateStatus.UpToDate
                }
            }
        }.getOrElse { UpdateStatus.Failed(it.message ?: "Couldn't reach GitHub.") }
    }

    fun download(release: ReleaseInfo): Flow<DownloadProgress> = flow {
        val target = File(context.cacheDir, "update-${release.versionName}.apk")
        target.delete()

        val request = Request.Builder()
            .url(release.downloadUrl)
            .header("User-Agent", "MasterKey/${BuildConfig.VERSION_NAME}")
            .build()

        val result = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadProgress.Failed("Download failed (${response.code})."))
                    return@flow
                }
                val body = response.body ?: run {
                    emit(DownloadProgress.Failed("Download was empty."))
                    return@flow
                }
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                var read = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            emit(DownloadProgress.Downloading(read, total))
                        }
                    }
                }
                target
            }
        }

        result.fold(
            onSuccess = { emit(DownloadProgress.Complete(it)) },
            onFailure = {
                target.delete()
                emit(DownloadProgress.Failed(it.message ?: "Download failed."))
            },
        )
    }.flowOn(Dispatchers.IO)

    /** True once the user has granted "install unknown apps" for this app. */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * Streams the APK straight into a [PackageInstaller] session.
     *
     * Expect a system confirmation dialog every time: silent install requires
     * being the installer of record, and the first install here is a manual
     * sideload, so we never are. Designing for the dialog avoids building on an
     * assumption that may not hold.
     */
    suspend fun install(apk: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }

                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    // MUTABLE is required: the system fills in the status extras.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        }
    }

    /** Last-resort path if the installer fails: open the release in a browser. */
    fun releasePageIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun parseRelease(json: JSONObject): ReleaseInfo? {
        val tag = json.optString("tag_name").ifBlank { return null }
        val versionName = tag.removePrefix("v")
        val versionCode = versionCodeFromName(versionName) ?: return null

        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            return ReleaseInfo(
                tag = tag,
                versionName = versionName,
                versionCode = versionCode,
                notes = json.optString("body").trim(),
                downloadUrl = asset.optString("browser_download_url").ifBlank { return null },
                sizeBytes = asset.optLong("size"),
            )
        }
        return null
    }

    companion object {
        const val OWNER = "kaiharimoto"
        const val REPO = "Master-Key"

        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        private const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases/latest"

        /**
         * Mirror of the formula in `app/build.gradle.kts`. These two must stay in
         * step: the build stamps the APK with it, and the app compares against it.
         */
        fun versionCodeFromName(versionName: String): Long? {
            val parts = versionName.trim().split(".")
            if (parts.size != 3) return null
            val numbers = parts.map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
            }
            return (numbers[0] * 10000 + numbers[1] * 100 + numbers[2]).toLong()
        }
    }
}
