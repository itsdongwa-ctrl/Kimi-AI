package com.kimi.ai

import android.content.Context
import android.content.SharedPreferences

/** Non-sensitive per-mode settings. */
object Prefs {
    fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences("kimi_settings", Context.MODE_PRIVATE)
    fun str(ctx: Context, k: String, d: String) = p(ctx).getString(k, d) ?: d
    fun int(ctx: Context, k: String, d: Int) = p(ctx).getInt(k, d)
    fun bool(ctx: Context, k: String, d: Boolean) = p(ctx).getBoolean(k, d)
}
