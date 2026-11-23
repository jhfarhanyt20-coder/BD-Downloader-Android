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
    private const val REPO = "/jhfarhanyt20-coder/VibeDownloader-Android/"
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

    /** After the user came back from the "Install unknown apps" page. */
    suspend fun continueAfterPermission(context: Context): Unit = withContext(Dispatchers.IO) {
        val s = _state.value
        if (s is State.NeedsPermission && canInstall(context)) install(context, s.release, s.apk)
    }

    fun dismissProblem() {
        if (_state.value is State.Problem) _state.value = State.Idle
    }
}
