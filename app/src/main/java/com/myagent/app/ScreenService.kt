package com.myagent.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenInfo(val pkg: String, val text: String, val nodes: List<AccessibilityNodeInfo>)

class ScreenService : AccessibilityService() {
    companion object {
        @Volatile var inst: ScreenService? = null
        private val blocked = listOf(
            "bank", "pay", "wallet", "bok", "bankak", "fawry", "finance", "credit",
            "packageinstaller", "permissioncontroller", "com.android.settings", "securitycenter"
        )

        fun isBlocked(pkg: String): Boolean {
            val p = pkg.lowercase()
            return blocked.any { p.contains(it) }
        }
    }

    override fun onServiceConnected() {
        inst = this
        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        inst = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        inst = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun readScreen(): ScreenInfo? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: ""
        if (isBlocked(pkg)) return ScreenInfo(pkg, "BLOCKED", emptyList())
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        walk(root, 0, nodes, sb)
        return ScreenInfo(pkg, sb.toString(), nodes)
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, nodes: ArrayList<AccessibilityNodeInfo>, sb: StringBuilder) {
        if (n == null || depth > 30 || nodes.size >= 80) return
        if (n.isVisibleToUser) {
            val txt = if (n.isPassword) "" else (n.text?.toString() ?: "")
            val dsc = n.contentDescription?.toString() ?: ""
            val useful = txt.isNotBlank() || dsc.isNotBlank() || n.isClickable || n.isEditable
            if (useful) {
                val i = nodes.size
                nodes.add(n)
                val cls = (n.className?.toString() ?: "").substringAfterLast('.')
                sb.append("[").append(i).append("] ").append(cls)
                if (n.isPassword) sb.append(" (password field - hidden)")
                if (txt.isNotBlank()) sb.append(" \"").append(txt.take(80)).append("\"")
                if (dsc.isNotBlank() && dsc != txt) sb.append(" desc=\"").append(dsc.take(60)).append("\"")
                if (n.isClickable) sb.append(" clickable")
                if (n.isEditable) sb.append(" editable")
                if (n.isScrollable) sb.append(" scrollable")
                if (n.isChecked) sb.append(" checked")
                sb.append("\n")
            }
        }
        for (k in 0 until n.childCount) {
            walk(n.getChild(k), depth + 1, nodes, sb)
        }
    }

    private fun tap(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 60L))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun clickAt(nodes: List<AccessibilityNodeInfo>, idx: Int): Boolean {
        val first = nodes.getOrNull(idx) ?: return false
        var n: AccessibilityNodeInfo? = first
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.isEnabled) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            n = n.parent
            hops++
        }
        val r = Rect()
        first.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    fun setText(nodes: List<AccessibilityNodeInfo>, idx: Int, text: String): Boolean {
        val n = nodes.getOrNull(idx) ?: return false
        if (n.isPassword) return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    private fun findScrollable(n: AccessibilityNodeInfo?, d: Int): AccessibilityNodeInfo? {
        if (n == null || d > 30) return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val r = findScrollable(n.getChild(i), d + 1)
            if (r != null) return r
        }
        return null
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val sc = findScrollable(root, 0) ?: return false
        return sc.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun enter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }
}
