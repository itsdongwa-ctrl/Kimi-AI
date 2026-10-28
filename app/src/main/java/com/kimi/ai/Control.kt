package com.kimi.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent

object Control {
    private const val NEED = "Enable Kimi AI in Accessibility settings first (God Mode)."

    fun openUrl(ctx: Context, url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun youtube(ctx: Context, url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Prefs.int(ctx, "n_yt", 0) == 0) {
            try { ctx.startActivity(Intent(i).setPackage("com.google.android.youtube")); return }
            catch (e: Exception) { /* app missing: fall back to browser */ }
        }
        ctx.startActivity(i)
    }

    fun media(ctx: Context, key: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    private fun global(a: Int): String {
        val s = KimiAccessibilityService.instance ?: return NEED
        s.performGlobalAction(a); return "Done"
    }

    fun run(ctx: Context, raw: String): String {
        val c = raw.trim().lowercase()
        return when {
            c == "open youtube" -> { youtube(ctx, "https://www.youtube.com"); "Opening YouTube" }
            c.startsWith("search youtube ") -> {
                youtube(ctx, "https://www.youtube.com/results?search_query=" + Uri.encode(raw.trim().substring(15)))
                "Searching YouTube"
            }
            c == "play" || c == "pause" -> { media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); "Play/Pause sent" }
            c == "next" -> { media(ctx, KeyEvent.KEYCODE_MEDIA_NEXT); "Next" }
            c == "previous" -> { media(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Previous" }
            c == "skip ad" -> tap(Prefs.str(ctx, "g_skip", "Skip"))
            c == "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            c == "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            c == "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            c == "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            c == "scroll down" -> KimiAccessibilityService.instance?.let { it.scroll(true); "Scrolled" } ?: NEED
            c == "scroll up" -> KimiAccessibilityService.instance?.let { it.scroll(false); "Scrolled" } ?: NEED
            c.startsWith("click ") || c.startsWith("tap ") -> tap(raw.trim().substringAfter(' ').trim())
            else -> "Not sure yet. Try: open youtube, search youtube lofi, pause, next, home, back, scroll down, click Subscribe"
        }
    }

    fun tap(text: String): String {
        val s = KimiAccessibilityService.instance ?: return NEED
        return if (s.clickText(text)) "Tapped \"$text\"" else "Couldn't find \"$text\" on screen"
    }
}
