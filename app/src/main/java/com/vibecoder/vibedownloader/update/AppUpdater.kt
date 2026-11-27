package com.vibecoder.vibedownloader.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.vibecoder.vibedownloader.BuildConfig
import com.vibecoder.vibedownloader.Prefs
import com.vibecoder.vibedownloader.engine.AppLog
import com.vibecoder.vibedownloader.engine.EngineUpdater
import com.vibecoder.vibedownloader.engine.SafeFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Updates the app from the official GitHub release of this project:
 * latest.json -> APK in the cache -> size + SHA-256 check -> PackageInstaller.
 * It never installs an older or equal version.
 */
object AppUpdater {
    private const val REPO = "/jhfarhanyt20-coder/BD-Downloader-Android/"
    private const val LATEST = "https://github.com${REPO}releases/latest/download/latest.json"
    private const val INTERVAL = 12L * 60 * 60 * 1000
    private const val MAX_APK = 300L * 1024 * 1024

    data class Release(val version: String, val url: String, val size: Long, val sha256: String)

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val progress: Float) : State()
        /** Downloaded and checked: the user taps "Install now". */
        class Ready(val release: Release, val apk: File) : State()
        /** Android needs "Install unknown apps" allowed for VibeDownloader first. */
        class NeedsPermission(val release: Release, val apk: File) : State()
        data class Installing(val release: Release) : State()
        data class Problem(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** On start: only when automatic checks are on, and at most every 12 hours. */
    suspend fun checkIfDue(context: Context) {
        val prefs = Prefs(context)
        if (!prefs.autoUpdate) return
        if (System.currentTimeMillis() - prefs.lastAppUpdateCheck < INTERVAL) return
        check(context, quiet = true)
        // automatic updates: download it quietly, then offer "Install now" (in the app and as a notification)
        if (_state.value is State.Available && (!prefs.wifiOnly || com.vibecoder.vibedownloader.engine.Net.state(context) == com.vibecoder.vibedownloader.engine.Net.State.Unmetered)) {
            if (download(context)) notifyReady(context)
        }
    }

    /** Downloads and checks the new version. True when it is ready to install. */
    suspend fun download(context: Context): Boolean = withContext(Dispatchers.IO) {
        val s0 = _state.value
        if (s0 is State.Ready) return@withContext true
        val release = when (s0) {
            is State.Available -> s0.release
            is State.Problem, State.UpToDate, State.Idle, State.Checking -> { check(context); (_state.value as? State.Available)?.release }
            else -> null
        } ?: return@withContext false
        try {
            checkUrl(release.url)
            if (release.size <= 0 || release.size > MAX_APK) throw IOException("Unexpected update size")
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "VibeDownloader-${release.version}.apk")
            _state.value = State.Downloading(release, 0f)
            SafeFetch.open(release.url, SafeFetch.githubAllow(REPO)).use { resp ->
                val body = resp.body ?: throw IOException("Empty answer")
                apk.outputStream().use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total > release.size) throw IOException("Update is bigger than announced")
                        if (total - lastShown > 256 * 1024) {
                            lastShown = total
                            _state.value = State.Downloading(release, total.toFloat() / release.size)
                        }
                    }
                }
            }
            if (apk.length() != release.size) throw IOException("Update size does not match")
            val sha = apk.inputStream().use { SafeFetch.sha256(it) }
            if (!sha.equals(release.sha256, ignoreCase = true)) throw IOException("Update failed the SHA-256 check")
            verifyPackage(context, apk)
            _state.value = State.Ready(release, apk)
            AppLog.i("update", "Version ${release.version} downloaded and ready to install")
            true
        } catch (e: Exception) {
            AppLog.w("update", "Update download failed", e)
            _state.value = State.Problem("The update couldn't be downloaded. Please try again.")
            false
        }
    }

    /** "Install now": installs the downloaded version (downloads it first if needed). */
    suspend fun installNow(context: Context): Unit = withContext(Dispatchers.IO) {
        when (val s = _state.value) {
            is State.Ready -> install(context, s.release, s.apk)
            is State.NeedsPermission -> install(context, s.release, s.apk)
            else -> if (download(context)) (_state.value as? State.Ready)?.let { install(context, it.release, it.apk) }
        }
    }

    /** A notification when an update finished downloading on its own. */
    private fun notifyReady(context: Context) {
        val s = _state.value as? State.Ready ?: return
        if (!com.vibecoder.vibedownloader.service.Notifier.canPost(context)) return
        runCatching {
            val open = PendingIntent.getActivity(
                context, 9100,
                Intent(context, com.vibecoder.vibedownloader.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = androidx.core.app.NotificationCompat.Builder(context, com.vibecoder.vibedownloader.service.Notifier.CHANNEL_RESULT)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("BD Downloader ${s.release.version} is ready")
                .setContentText("Tap to install the new version.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            androidx.core.app.NotificationManagerCompat.from(context).notify(9100, n)
        }
    }

    suspend fun check(context: Context, quiet: Boolean = false): Unit = withContext(Dispatchers.IO) {
        if (_state.value is State.Downloading || _state.value is State.Installing) return@withContext
        if (!quiet) _state.value = State.Checking
        try {
            val json = SafeFetch.open(LATEST, SafeFetch.githubAllow(REPO)).use { it.body?.string() ?: "" }
            val o = JSONObject(json)
            val r = Release(o.getString("version").trim().removePrefix("v"), o.getString("url"), o.getLong("size"), o.getString("sha256").lowercase())
            Prefs(context).lastAppUpdateCheck = System.currentTimeMillis()
            checkUrl(r.url)
            _state.value = if (isNewer(r.version, BuildConfig.VERSION_NAME)) State.Available(r) else State.UpToDate
            AppLog.i("update", "Latest ${r.version}, this ${BuildConfig.VERSION_NAME}")
        } catch (e: Exception) {
            AppLog.w("update", "Update check failed", e)
            _state.value = if (quiet) State.Idle else State.Problem("Couldn't check for updates. Please try again later.")
        }
    }

    /** The APK must come from this project's own GitHub releases, over https. */
    private fun checkUrl(url: String) {
        val u = Uri.parse(url)
        if (u.scheme != "https" || u.host != "github.com" || u.path?.startsWith(REPO + "releases/download/") != true) {
            throw IOException("Update address is not the official release")
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = EngineUpdater.isNewer(candidate, current)

    suspend fun downloadAndInstall(context: Context): Unit = withContext(Dispatchers.IO) {
        val release = when (val s = _state.value) {
            is State.Available -> s.release
            is State.Problem, State.UpToDate, State.Idle, State.Checking -> { check(context); (_state.value as? State.Available)?.release }
            is State.NeedsPermission -> { install(context, s.release, s.apk); return@withContext }
            else -> null
        } ?: return@withContext
        try {
            checkUrl(release.url)
            if (release.size <= 0 || release.size > MAX_APK) throw IOException("Unexpected update size")
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "VibeDownloader-${release.version}.apk")
            _state.value = State.Downloading(release, 0f)
            SafeFetch.open(release.url, SafeFetch.githubAllow(REPO)).use { resp ->
                val body = resp.body ?: throw IOException("Empty answer")
                apk.outputStream().use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total > release.size) throw IOException("Update is bigger than announced")
                        if (total - lastShown > 256 * 1024) {
                            lastShown = total
                            _state.value = State.Downloading(release, total.toFloat() / release.size)
                        }
                    }
                }
            }
            if (apk.length() != release.size) throw IOException("Update size does not match")
            val sha = apk.inputStream().use { SafeFetch.sha256(it) }
            if (!sha.equals(release.sha256, ignoreCase = true)) throw IOException("Update failed the SHA-256 check")
            verifyPackage(context, apk)
            install(context, release, apk)
        } catch (e: Exception) {
            AppLog.w("update", "Update download failed", e)
            _state.value = State.Problem("The update couldn't be downloaded. Please try again.")
        }
    }

    /** Same app, and really newer: never a downgrade. */
    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, apk: File) {
        val pm = context.packageManager
        val info: PackageInfo = pm.getPackageArchiveInfo(apk.path, 0) ?: throw IOException("The update file is damaged")
        if (info.packageName != context.packageName) throw IOException("The update is for a different app")
        val current = pm.getPackageInfo(context.packageName, 0)
        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) current.longVersionCode else current.versionCode.toLong()
        if (newCode <= oldCode) throw IOException("The update is not newer than this version")
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the user allows "Install unknown apps" for VibeDownloader. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun install(context: Context, release: Release, apk: File) {
        if (!canInstall(context)) {
            _state.value = State.NeedsPermission(release, apk)
            return
        }
        _state.value = State.Installing(release)
        // The phone's own installer screen, opened straight from the user's tap. (A confirmation
        // started later from the background is blocked on new Android versions; this never is.)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", apk)
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (view.resolveActivity(context.packageManager) != null) {
                context.startActivity(view)
                AppLog.i("update", "Opened the system installer for ${release.version}")
                return
            }
        } catch (e: Exception) {
            AppLog.w("update", "System installer did not open, using the package installer session", e)
        }
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            params.setSize(apk.length())
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val callback = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION),
                    flags,
                )
                session.commit(callback.intentSender)
            }
            AppLog.i("update", "Installing ${release.version}")
        } catch (e: Exception) {
            AppLog.w("update", "Install failed to start", e)
            _state.value = State.Problem("The update couldn't be installed. Please try again.")
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = (_state.value as? State.Installing)?.let { State.Available(it.release) } ?: State.Idle
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            else -> {
                AppLog.w("update", "Install result $status: $message")
                _state.value = State.Problem("The update couldn't be installed. Please try again.")
            }
        }
    }

    /** After the user came back from the "Install unknown apps" page, or closed the installer without installing. */
    suspend fun continueAfterPermission(context: Context): Unit = withContext(Dispatchers.Main) {
        val s = _state.value
        if (s is State.NeedsPermission && canInstall(context)) install(context, s.release, s.apk)
        else if (s is State.Installing) {
            // still the old version: the user backed out of the installer, so offer "Install now" again
            val apk = File(File(context.cacheDir, "update"), "VibeDownloader-${s.release.version}.apk")
            _state.value = if (apk.exists()) State.Ready(s.release, apk) else State.Available(s.release)
        }
    }

    // ---- versions too old to keep working ----
    // Every release says which is the oldest version still allowed (the last three releases).
    // An older app shows only "Update required" until it is updated. The last answer is kept,
    // so it also holds without internet.
    private val _required = MutableStateFlow<String?>(null)
    /** Non-null = this version is too old; the value is the oldest version still allowed. */
    val required: StateFlow<String?> = _required

    fun loadRequired(context: Context) {
        val min = Prefs(context).minAppVersion
        _required.value = if (min != null && isNewer(min, BuildConfig.VERSION_NAME)) min else null
    }

    /** On every start: a light check of latest.json, only for the oldest allowed version. */
    suspend fun checkRequired(context: Context): Unit = withContext(Dispatchers.IO) {
        try {
            val json = SafeFetch.open(LATEST, SafeFetch.githubAllow(REPO)).use { it.body?.string() ?: "" }
            val min = JSONObject(json).optString("min_version").trim().removePrefix("v").ifBlank { null }
            Prefs(context).minAppVersion = min
            loadRequired(context)
            if (_required.value != null) {
                AppLog.w("update", "This version is too old (oldest allowed $min): update required")
                if (_state.value is State.Idle || _state.value is State.UpToDate) check(context, quiet = true)
            }
        } catch (e: Exception) {
            AppLog.w("update", "Could not check the oldest allowed version", e)
        }
    }

    fun dismissProblem() {
        if (_state.value is State.Problem) _state.value = State.Idle
    }
}
