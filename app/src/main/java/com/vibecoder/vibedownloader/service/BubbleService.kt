package com.vibecoder.vibedownloader.service

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vibecoder.vibedownloader.Prefs
import com.vibecoder.vibedownloader.ShareActivity
import com.vibecoder.vibedownloader.data.DownloadRepository
import com.vibecoder.vibedownloader.data.Status
import com.vibecoder.vibedownloader.engine.AppLog
import com.vibecoder.vibedownloader.extractLink
import com.vibecoder.vibedownloader.isMediaLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** The apps the smart button appears over (Android 10 and newer). Kept in one place. */
object SmartApps {
    val PACKAGES = setOf(
        "com.google.android.youtube",              // YouTube
        "com.google.android.apps.youtube.music",   // YouTube Music
        "com.facebook.katana", "com.facebook.lite", // Facebook
        "com.instagram.android",                   // Instagram
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", // TikTok
        "com.twitter.android",                     // X / Twitter
        "com.android.chrome", "com.chrome.beta",   // Chrome
        "com.sec.android.app.sbrowser",            // Samsung Internet
        "org.mozilla.firefox",                     // Firefox
        "com.brave.browser",                       // Brave
        "com.microsoft.emmx",                      // Edge
        "com.opera.browser", "com.opera.mini.native", // Opera
    )
}

/**
 * The smart floating download button. It never reads other apps' screens.
 *  - Android 8 and 9: it pops up for about 12 seconds when a video or audio link is copied.
 *  - Android 10+: background apps can't see the clipboard, so it shows only while a
 *    supported video app or browser is open (Usage access tells which app is open,
 *    nothing more). Tapping it opens the download sheet, which reads the clipboard
 *    itself because it is the focused app then.
 * Foreground service type "specialUse" (Android 14) with its subtype property in the manifest.
 */
class BubbleService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var closeTarget: CloseView? = null
    private var shown = false
    private var clipLink: String? = null
    private var currentApp: String? = null
    private var snoozedFor: String? = null
    private var lastQuery = 0L
    private var polling = false
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val dp get() = resources.displayMetrics.density
    private val usesClipboard = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // One service keeps VibeDownloader running in the background (one notification, with Stop),
        // and hosts the smart download button when that is turned on.
        smart = Prefs(this).smartButton && ready(this)
        val hint = when {
            !smart -> "Ready for your next download. Tap Stop to close it."
            usesClipboard -> "Smart button: appears when you copy a video link"
            else -> "Smart button: appears while a video app or browser is open"
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, Notifier.BUBBLE_ID, Notifier.bubble(this, hint), type)
        } catch (e: Exception) {
            AppLog.w("bubble", "Could not start in the foreground", e)
            stopSelf()
            return
        }
        running = true
        if (smart) { if (usesClipboard) watchClipboard() else watchScreen() }
        // a tiny progress ring while downloads run
        scope.launch {
            DownloadRepository.items.collectLatest { items ->
                val active = items.filter { it.status == Status.Downloading || it.status == Status.Finishing }
                val p = when {
                    active.isEmpty() -> null
                    active.all { it.total > 0 } -> active.sumOf { it.downloaded }.toFloat() / active.sumOf { it.total }.coerceAtLeast(1)
                    else -> -1f
                }
                bubble?.progress = p
            }
        }
        AppLog.i("bubble", if (smart) "Running in the background, smart button on (${if (usesClipboard) "copied links" else "video apps"})" else "Running in the background")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!wanted(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    // ------------------------------------------------------------ Android 8-9: copied links
    private fun watchClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val l = ClipboardManager.OnPrimaryClipChangedListener {
            val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
            val link = extractLink(text)
            if (link != null && isMediaLink(link)) {
                clipLink = link
                show()
                main.removeCallbacks(autoHide)
                main.postDelayed(autoHide, 12_000)
            }
        }
        clipListener = l
        runCatching { cm.addPrimaryClipChangedListener(l) }
    }

    private val autoHide = Runnable { hide() }

    // ------------------------------------------------------------ Android 10+: which app is open
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { stopPolling(); hide() }
                Intent.ACTION_SCREEN_ON -> startPolling()
            }
        }
    }

    private fun watchScreen() {
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        runCatching { ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED) }
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive != false) startPolling()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            checkForegroundApp()
            main.postDelayed(this, 1000)
        }
    }

    private fun startPolling() {
        if (polling || usesClipboard) return
        polling = true
        lastQuery = System.currentTimeMillis() - 60_000
        main.post(poll)
    }

    private fun stopPolling() {
        polling = false
        main.removeCallbacks(poll)
    }

    private fun checkForegroundApp() {
        if (!hasUsageAccess(this)) return
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        var latest: String? = null
        runCatching {
            val events = usm.queryEvents(lastQuery, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                @Suppress("DEPRECATION")
                val resumed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                else e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (resumed) latest = e.packageName
            }
        }
        lastQuery = now - 1500   // a small overlap, so an event at the edge is never missed
        val app = latest ?: currentApp
        if (app != currentApp) {
            currentApp = app
            if (snoozedFor != null && snoozedFor != app) snoozedFor = null
        }
        val want = app != null && app in SmartApps.PACKAGES && snoozedFor != app
        if (want && !shown) show() else if (!want && shown) hide()
    }

    // ------------------------------------------------------------ the bubble
    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (!Settings.canDrawOverlays(this)) return
        val view = bubble ?: BubbleView(this).also { v ->
            val size = (64 * dp).toInt()
            val prefs = Prefs(this)
            val sw = resources.displayMetrics.widthPixels
            val sh = resources.displayMetrics.heightPixels
            val p = WindowManager.LayoutParams(
                size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (prefs.bubbleRight) sw - size - (6 * dp).toInt() else (6 * dp).toInt()
                y = prefs.bubbleY.takeIf { it in 0..sh } ?: (sh * 0.55f).toInt()
            }
            bubbleParams = p
            v.setOnTouchListener(DragHandler())
            v.contentDescription = "Download with VibeDownloader"
            bubble = v
        }
        if (!shown) {
            shown = true
            if (view.parent == null) runCatching { wm.addView(view, bubbleParams) }.onFailure { AppLog.w("bubble", "Could not show", it); shown = false; return }
            view.animate().cancel()
            view.alpha = 0f; view.scaleX = 0.6f; view.scaleY = 0.6f
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.6f)).start()
        }
    }

    private fun hide() {
        main.removeCallbacks(autoHide)
        val view = bubble ?: return
        if (!shown) return
        shown = false
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(200).withEndAction {
            if (!shown && view.parent != null) runCatching { wm.removeView(view) }
        }.start()
        hideClose()
    }

    private fun onTap() {
        val link = clipLink
        val i = Intent(this, ShareActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ShareActivity.EXTRA_FROM_BUBBLE, true)
        if (link != null && usesClipboard) i.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        runCatching { startActivity(i) }.onFailure { AppLog.w("bubble", "Could not open the sheet", it) }
        if (usesClipboard) { clipLink = null; hide() }
    }

    private fun showClose() {
        val c = closeTarget ?: CloseView(this).also { closeTarget = it }
        if (c.parent == null) {
            val size = (72 * dp).toInt()
            val p = WindowManager.LayoutParams(
                size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (56 * dp).toInt() }
            runCatching { wm.addView(c, p) }
        }
        c.animate().cancel()
        c.alpha = 0f; c.translationY = 40 * dp
        c.animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    private fun hideClose() {
        val c = closeTarget ?: return
        if (c.parent == null) return
        c.animate().cancel()
        c.animate().alpha(0f).translationY(40 * dp).setDuration(160).withEndAction {
            if (c.parent != null) runCatching { wm.removeView(c) }
        }.start()
    }

    /** Is the finger over the × target at the bottom centre? */
    private fun nearClose(rawX: Float, rawY: Float): Boolean {
        val cx = resources.displayMetrics.widthPixels / 2f
        val cy = resources.displayMetrics.heightPixels - (56 + 36) * dp
        return hypot(rawX - cx, rawY - cy) < 90 * dp
    }

    private inner class DragHandler : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                    main.removeCallbacks(autoHide)
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; showClose() }
                    if (dragging) {
                        p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, p) }
                        closeTarget?.hot = nearClose(e.rawX, e.rawY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (!dragging) {
                        if (e.actionMasked == MotionEvent.ACTION_UP) { v.performClick(); onTap() }
                    } else if (nearClose(e.rawX, e.rawY)) {
                        // dismissed: hidden until another app is opened (or the next copied link)
                        snoozedFor = currentApp
                        clipLink = null
                        hide()
                        Toast.makeText(this@BubbleService, "Hidden for now. Turn it off any time in Settings.", Toast.LENGTH_SHORT).show()
                    } else {
                        hideClose()
                        snapToEdge(v, p)
                        if (usesClipboard && shown) main.postDelayed(autoHide, 12_000)
                    }
                }
            }
            return true
        }
    }

    private fun snapToEdge(v: View, p: WindowManager.LayoutParams) {
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val size = p.width
        val right = p.x + size / 2 > sw / 2
        val targetX = if (right) sw - size - (6 * dp).toInt() else (6 * dp).toInt()
        p.y = p.y.coerceIn((24 * dp).toInt(), sh - size - (48 * dp).toInt())
        val fromX = p.x
        android.animation.ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { a ->
                p.x = a.animatedValue as Int
                if (v.parent != null) runCatching { wm.updateViewLayout(v, p) }
            }
            start()
        }
        Prefs(this).apply { bubbleRight = right; bubbleY = p.y }
    }

    override fun onDestroy() {
        running = false
        stopPolling()
        main.removeCallbacksAndMessages(null)
        if (!usesClipboard) runCatching { unregisterReceiver(screenReceiver) }
        clipListener?.let { l -> runCatching { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(l) } }
        bubble?.let { if (it.parent != null) runCatching { wm.removeViewImmediate(it) } }
        closeTarget?.let { if (it.parent != null) runCatching { wm.removeViewImmediate(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private var smart = false

    companion object {
        const val ACTION_STOP = "com.vibecoder.vibedownloader.STOP_BACKGROUND"
        @Volatile private var running = false

        /** Run in the background (the default), or for the smart button. */
        fun wanted(context: Context): Boolean {
            val p = Prefs(context)
            return p.keepRunning || (p.smartButton && ready(context))
        }
        val isRunning: Boolean get() = running

        /** Android 10+ needs "Usage access" to know which app is open. */
        fun needsUsageAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
                }
            }.getOrDefault(AppOpsManager.MODE_ERRORED)
            return if (mode == AppOpsManager.MODE_DEFAULT) {
                context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else mode == AppOpsManager.MODE_ALLOWED
        }

        /** Everything the button needs is allowed. */
        fun ready(context: Context): Boolean =
            Settings.canDrawOverlays(context) && (!needsUsageAccess() || hasUsageAccess(context))

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
            } catch (e: Exception) {
                AppLog.w("bubble", "Could not start the smart button: ${e.message}")
            }
        }

        fun startIfEnabled(context: Context) {
            if (wanted(context) && !running) start(context)
        }

        /** After a setting changed: start again with the new settings (or stop if nothing needs it). */
        fun refresh(context: Context) {
            stop(context)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startIfEnabled(context) }, 400)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BubbleService::class.java)) }
        }
    }
}

/** The round gradient button with a download arrow and a thin progress ring. */
private class BubbleView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.6f * d
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF; style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7CF3FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val arc = RectF()
    private var spin = 0f

    /** null = no downloads, -1 = running with unknown size, 0..1 = progress. */
    var progress: Float? = null
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 5 * d
        c.drawCircle(cx, cy + 1.5f * d, r + 1.5f * d, shadow)
        fill.shader = null
        fill.color = 0xFF6A3DF5.toInt() // solid accent colour (no gradients)
        c.drawCircle(cx, cy, r, fill)
        // arrow down onto a tray
        val s = r * 0.42f
        path.reset()
        path.moveTo(cx, cy - s); path.lineTo(cx, cy + s * 0.45f)
        path.moveTo(cx - s * 0.55f, cy - s * 0.05f); path.lineTo(cx, cy + s * 0.5f); path.lineTo(cx + s * 0.55f, cy - s * 0.05f)
        path.moveTo(cx - s * 0.75f, cy + s * 0.95f); path.lineTo(cx + s * 0.75f, cy + s * 0.95f)
        c.drawPath(path, icon)
        val p = progress ?: return
        val rr = r - 4.5f * d
        arc.set(cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawCircle(cx, cy, rr, track)
        if (p < 0f) {
            spin = (spin + 6f) % 360f
            c.drawArc(arc, spin - 90f, 80f, false, ring)
            postInvalidateOnAnimation()
        } else {
            c.drawArc(arc, -90f, 360f * p.coerceIn(0.02f, 1f), false, ring)
        }
    }
}

/** The × target at the bottom while the button is dragged; it grows when the button is over it. */
private class CloseView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC15171B.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val x = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.6f * d; strokeCap = Paint.Cap.ROUND
    }

    var hot = false
        set(v) {
            if (field == v) return
            field = v
            bg.color = if (v) 0xEEE46A61.toInt() else 0xCC15171B.toInt()
            animate().scaleX(if (v) 1.18f else 1f).scaleY(if (v) 1.18f else 1f).setDuration(120).start()
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 6 * d
        c.drawCircle(cx, cy, r, bg)
        c.drawCircle(cx, cy, r, edge)
        val s = r * 0.32f
        c.drawLine(cx - s, cy - s, cx + s, cy + s, x)
        c.drawLine(cx + s, cy - s, cx - s, cy + s, x)
    }
}
