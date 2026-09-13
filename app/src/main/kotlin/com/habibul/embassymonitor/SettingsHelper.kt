package com.habibul.embassymonitor

import android.content.Context

object SettingsHelper {
    private const val PREFS = "embassy_settings"

    const val DEFAULT_INTERVAL = 5L

    private const val KEY_INTERVAL       = "interval_min"
    private const val KEY_SOUND          = "sound_on"
    private const val KEY_VIBRATION      = "vib_on"
    private const val KEY_NOTIFY_CHANGED = "notify_changed"
    private const val KEY_NOTIFY_REMOVED = "notify_removed"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getInterval(ctx: Context)        : Long    = p(ctx).getLong   (KEY_INTERVAL,       DEFAULT_INTERVAL)
    fun setInterval(ctx: Context, v: Long)         { p(ctx).edit().putLong   (KEY_INTERVAL,       v).apply() }

    fun isSoundEnabled(ctx: Context)     : Boolean = p(ctx).getBoolean(KEY_SOUND,          true)
    fun setSoundEnabled(ctx: Context, v: Boolean)  { p(ctx).edit().putBoolean(KEY_SOUND,          v).apply() }

    fun isVibrationEnabled(ctx: Context) : Boolean = p(ctx).getBoolean(KEY_VIBRATION,      true)
    fun setVibrationEnabled(ctx: Context, v: Boolean){ p(ctx).edit().putBoolean(KEY_VIBRATION,      v).apply() }

    fun notifyChanged(ctx: Context)      : Boolean = p(ctx).getBoolean(KEY_NOTIFY_CHANGED, true)
    fun setNotifyChanged(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean(KEY_NOTIFY_CHANGED, v).apply() }

    fun notifyRemoved(ctx: Context)      : Boolean = p(ctx).getBoolean(KEY_NOTIFY_REMOVED, true)
    fun setNotifyRemoved(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean(KEY_NOTIFY_REMOVED, v).apply() }
}
