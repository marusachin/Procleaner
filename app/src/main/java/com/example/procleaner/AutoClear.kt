package com.example.procleaner

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

fun a11yEnabled(ctx: Context): Boolean {
    val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return s.contains(ctx.packageName + "/")
}

/** Drives Settings > App info > Storage > Clear cache for a queue of apps. */
object AutoClear {
    var running by mutableStateOf(false)
    var left by mutableIntStateOf(0)
    var cleared by mutableIntStateOf(0)
    var step = 0            // 0 = look for "Storage", 1 = look for "Clear cache", 2 = done with this app
    var current: String? = null
    var service: CacheClearService? = null

    private var queue = ArrayDeque<String>()
    private var appCtx: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var token = 0

    fun start(c: Context, pkgs: List<String>) {
        if (pkgs.isEmpty()) return
        appCtx = c.applicationContext
        queue = ArrayDeque(pkgs)
        cleared = 0
        left = pkgs.size
        running = true
        next()
    }

    fun stop() {
        running = false
        token++
        current = null
    }

    private fun launchDetails(c: Context, pkg: String) {
        c.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun next() {
        val c = appCtx ?: return
        val pkg = queue.removeFirstOrNull()
        if (pkg == null) { finish(); return }
        left = queue.size
        current = pkg
        step = 0
        val my = ++token
        try { launchDetails(c, pkg) } catch (e: Exception) { stop(); return }
        // watchdog: if this app takes too long (e.g. cache already 0), skip to the next one
        handler.postDelayed({
            if (running && token == my) {
                service?.back()
                handler.postDelayed({ if (running) next() }, 600)
            }
        }, 10000)
    }

    fun afterClear() {
        val my = token
        handler.postDelayed({ service?.back() }, 700)
        handler.postDelayed({ service?.back() }, 1300)
        handler.postDelayed({ if (running && token == my) next() }, 1900)
    }

    private fun finish() {
        running = false
        token++
        current = null
        val c = appCtx ?: return
        try {
            c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(it)
            }
        } catch (e: Exception) { }
    }
}

class CacheClearService : AccessibilityService() {
    override fun onServiceConnected() { AutoClear.service = this }
    override fun onInterrupt() {}
    override fun onDestroy() {
        if (AutoClear.service === this) AutoClear.service = null
        super.onDestroy()
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AutoClear.running || AutoClear.current == null) return
        val root = rootInActiveWindow ?: return
        when (AutoClear.step) {
            0 -> if (click(root, "Storage")) AutoClear.step = 1 else scrollDown(root)
            1 -> if (click(root, "Clear cache")) {
                AutoClear.step = 2
                AutoClear.cleared++
                AutoClear.afterClear()
            }
        }
    }

    private fun click(root: AccessibilityNodeInfo, text: String): Boolean {
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            if (!n.text?.toString().equals(text, true)) continue
            var p: AccessibilityNodeInfo? = n
            while (p != null) {
                if (p.isClickable && p.isEnabled) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                p = p.parent
            }
        }
        return false
    }

    private fun scrollDown(root: AccessibilityNodeInfo) {
        findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findScrollable(c)?.let { return it }
        }
        return null
    }
}
