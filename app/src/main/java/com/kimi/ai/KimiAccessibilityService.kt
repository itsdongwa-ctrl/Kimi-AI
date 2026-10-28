package com.kimi.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class KimiAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: KimiAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    fun scroll(down: Boolean) {
        val m = resources.displayMetrics
        val pct = listOf(40, 60, 80)[Prefs.int(this, "g_dist", 1).coerceIn(0, 2)] / 100f
        val ms = listOf(150L, 300L, 600L)[Prefs.int(this, "g_speed", 1).coerceIn(0, 2)]
        val x = m.widthPixels / 2f
        val mid = m.heightPixels / 2f
        val half = m.heightPixels * pct / 2f
        if (down) swipe(x, mid + half, x, mid - half, ms) else swipe(x, mid - half, x, mid + half, ms)
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }
}
