package com.douyin.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.File

/**
 * Douyin TV — GeckoView edition.
 *
 * Why GeckoView: the system WebView on older TV boxes / projectors (e.g. XGIMI Z8X,
 * Android 8.0, Chromium 62) cannot run douyin.com at all. GeckoView bundles a modern
 * Firefox engine inside the APK, so the device's WebView version no longer matters.
 *
 * Remote-control model:
 *  - Browse mode (default):  UP/DOWN = previous/next video, CENTER = play/pause,
 *                            LEFT/RIGHT = seek, BACK = history back / exit, MENU = cursor mode.
 *  - Cursor mode:            D-pad moves an on-screen pointer, CENTER = click,
 *                            long-press CENTER = start drag, CENTER again = drop.
 *                            This is how you tap login buttons or drag the slider captcha
 *                            when no mouse is attached. MENU/BACK leaves cursor mode.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DouyinTV"
        private const val HOME_URL = "https://www.douyin.com/jingxuan"
        private const val EXT_ID = "douyintv@local"
        private const val EXT_URI = "resource://android/assets/douyintv/"
        private const val NATIVE_APP = "douyintv"

        // One runtime per process — GeckoRuntime must never be created twice.
        @Volatile private var runtime: GeckoRuntime? = null
    }

    private lateinit var root: FrameLayout
    private lateinit var geckoView: GeckoView
    private lateinit var cursor: CursorOverlay
    private var session: GeckoSession? = null
    private var port: WebExtension.Port? = null
    private var canGoBack = false
    private val handler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullScreen()

        root = FrameLayout(this)
        geckoView = GeckoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
        }
        cursor = CursorOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        root.addView(geckoView)
        root.addView(cursor)
        setContentView(root)

        val rt = getOrCreateRuntime()
        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                // Desktop UA + desktop viewport: douyin.com serves the full web player
                // (with keyboard shortcuts) instead of the mobile H5 download page.
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                .useTrackingProtection(true)
                .build()
        )
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) { canGoBack = value }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) Log.w(TAG, "page load failed")
            }
        }
        s.open(rt)
        geckoView.setSession(s)
        session = s

        installExtension(rt, s)
        s.loadUri(HOME_URL)
        geckoView.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        session?.setActive(true)
        setupFullScreen()
    }

    override fun onPause() {
        super.onPause()
        // Pauses media + lets Gecko release GPU resources while in background.
        session?.setActive(false)
    }

    override fun onDestroy() {
        port?.disconnect()
        port = null
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun setupFullScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ---------------------------------------------------------------- runtime

    private fun getOrCreateRuntime(): GeckoRuntime {
        runtime?.let { return it }
        synchronized(MainActivity::class.java) {
            runtime?.let { return it }
            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .consoleOutput(false)
                .remoteDebuggingEnabled(false)
                .aboutConfigEnabled(false)
                // Low-RAM TV boxes: no site isolation, no separate extension process,
                // and let Gecko react to memory-pressure signals from Android.
                .fissionEnabled(false)
                .extensionsProcessEnabled(false)
                .lowMemoryDetection(true)
                .configFilePath(writePrefsFile().absolutePath)
                .build()
            return GeckoRuntime.create(applicationContext, settings).also { runtime = it }
        }
    }

    /**
     * Copies assets/geckoview-config.yaml to internal storage so Gecko can read it.
     * The file holds low-memory prefs (single content process, no Fission, small caches)
     * — this is what keeps a 1.6GB box from thrashing.
     */
    private fun writePrefsFile(): File {
        val out = File(filesDir, "geckoview-config.yaml")
        try {
            assets.open("geckoview-config.yaml").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write prefs file", e)
        }
        return out
    }

    // ---------------------------------------------------------------- extension

    private fun installExtension(rt: GeckoRuntime, s: GeckoSession) {
        rt.webExtensionController
            .ensureBuiltIn(EXT_URI, EXT_ID)
            .accept({ ext ->
                if (ext == null) return@accept
                // Content script calls browser.runtime.connectNative("douyintv");
                // registering on the *session* lets the content script talk to us directly.
                s.webExtensionController.setMessageDelegate(ext, messageDelegate, NATIVE_APP)
                Log.i(TAG, "extension ready")
            }, { e -> Log.e(TAG, "extension install failed", e) })
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(p: WebExtension.Port) {
            port = p
            p.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    // Page -> app events (e.g. {"evt":"toast","text":"..."}); kept for debugging.
                    Log.d(TAG, "from page: $message")
                }
                override fun onDisconnect(port: WebExtension.Port) {
                    if (this@MainActivity.port == port) this@MainActivity.port = null
                }
            })
        }
    }

    private fun send(cmd: String) {
        val p = port
        if (p == null) {
            // Extension not connected yet (page still loading) — fall back to raw key.
            Log.d(TAG, "port not ready, dropping $cmd")
            return
        }
        p.postMessage(JSONObject().put("cmd", cmd))
    }

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (cursor.visibility == View.VISIBLE) {
            if (handleCursorKey(event)) return true
        } else {
            if (event.action == KeyEvent.ACTION_DOWN && handleBrowseKey(event)) return true
            if (event.action == KeyEvent.ACTION_UP && isHandledBrowseKey(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isHandledBrowseKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_CHANNEL_DOWN -> true
        else -> false
    }

    private fun handleBrowseKey(event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT
            && event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return true // swallow autorepeat
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> send("prev")
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT -> send("next")
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> send("toggle")
            KeyEvent.KEYCODE_MEDIA_PLAY -> send("play")
            KeyEvent.KEYCODE_MEDIA_PAUSE -> send("pause")
            KeyEvent.KEYCODE_DPAD_LEFT -> send("seekBack")
            KeyEvent.KEYCODE_DPAD_RIGHT -> send("seekFwd")
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> enterCursorMode()
            KeyEvent.KEYCODE_BACK -> {
                if (canGoBack) session?.goBack() else moveTaskToBack(true)
            }
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- cursor mode

    private var dragging = false
    private var centerDownAt = 0L
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        if (!dragging) beginDrag()
    }

    private fun enterCursorMode() {
        cursor.visibility = View.VISIBLE
        cursor.resetToCenter()
        toast("光标模式：方向键移动，确认键点击，长按确认开始拖动，菜单键退出")
    }

    private fun exitCursorMode() {
        if (dragging) endDrag()
        cursor.visibility = View.GONE
    }

    private fun handleCursorKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> cursor.move(0f, -1f, event.repeatCount)
                KeyEvent.KEYCODE_DPAD_DOWN -> cursor.move(0f, 1f, event.repeatCount)
                KeyEvent.KEYCODE_DPAD_LEFT -> cursor.move(-1f, 0f, event.repeatCount)
                KeyEvent.KEYCODE_DPAD_RIGHT -> cursor.move(1f, 0f, event.repeatCount)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) {
                        centerDownAt = SystemClock.uptimeMillis()
                        longPressFired = false
                        handler.postDelayed(longPressRunnable, 450)
                    }
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_BACK -> exitCursorMode()
                else -> return false
            }
            if (dragging && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                        || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                injectTouch(MotionEvent.ACTION_MOVE)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                handler.removeCallbacks(longPressRunnable)
                when {
                    dragging && longPressFired -> { /* drag started on this press; keep holding */ }
                    dragging -> endDrag()          // second tap drops
                    !longPressFired -> click()     // short tap
                }
                longPressFired = false
            }
            return isHandledBrowseKey(code) || code == KeyEvent.KEYCODE_DPAD_CENTER
        }
        return false
    }

    private var downTime = 0L

    private fun click() {
        downTime = SystemClock.uptimeMillis()
        injectTouch(MotionEvent.ACTION_DOWN)
        handler.postDelayed({ injectTouch(MotionEvent.ACTION_UP) }, 60)
    }

    private fun beginDrag() {
        dragging = true
        downTime = SystemClock.uptimeMillis()
        cursor.setDragging(true)
        injectTouch(MotionEvent.ACTION_DOWN)
    }

    private fun endDrag() {
        dragging = false
        cursor.setDragging(false)
        injectTouch(MotionEvent.ACTION_UP)
    }

    /** Synthesises a touch at the cursor position and feeds it straight into Gecko. */
    private fun injectTouch(action: Int) {
        val s = session ?: return
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(downTime, now, action, cursor.px, cursor.py, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            s.panZoomController.onTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
