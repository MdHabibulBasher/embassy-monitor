package com.habibul.embassymonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ── History entry ─────────────────────────────────────────────────────────────
data class HistoryEntry(
    val type      : String,   // NEW | CHANGED | REMOVED
    val serviceId : Int,
    val serviceName: String,
    val timestamp : Long,
    val details   : String = ""
)

object PrefsHelper {

    private const val PREFS           = "embassy_prefs"
    private const val KEY_OPTIONS     = "known_options"
    private const val KEY_SEEDED      = "is_seeded"
    private const val KEY_LAST_CHECK  = "last_check_ts"
    private const val KEY_SVC_STATE   = "service_state_json"
    private const val KEY_HISTORY     = "history_json"
    private const val MAX_HISTORY     = 200

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Known options (Set<String>) ───────────────────────────────────────────
    fun getKnownOptions(ctx: Context): Set<String> =
        p(ctx).getStringSet(KEY_OPTIONS, emptySet())?.toSet() ?: emptySet()

    fun saveKnownOptions(ctx: Context, opts: Set<String>) {
        p(ctx).edit()
            .putStringSet(KEY_OPTIONS, HashSet(opts))
            .putBoolean  (KEY_SEEDED,  true)
            .putLong     (KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    fun isSeeded(ctx: Context): Boolean = p(ctx).getBoolean(KEY_SEEDED, false)
    fun getLastCheckTime(ctx: Context): Long = p(ctx).getLong(KEY_LAST_CHECK, 0L)

    // ── Service fingerprint state (Map<id, fingerprint>) ─────────────────────
    fun getServiceState(ctx: Context): Map<String, String> {
        val raw = p(ctx).getString(KEY_SVC_STATE, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) { emptyMap() }
    }

    fun saveServiceState(ctx: Context, state: Map<String, String>) {
        val obj = JSONObject()
        state.forEach { (k, v) -> obj.put(k, v) }
        p(ctx).edit().putString(KEY_SVC_STATE, obj.toString()).apply()
    }

    // ── History ───────────────────────────────────────────────────────────────
    fun getHistory(ctx: Context): List<HistoryEntry> {
        val raw = p(ctx).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    type        = o.getString("type"),
                    serviceId   = o.getInt("id"),
                    serviceName = o.getString("name"),
                    timestamp   = o.getLong("ts"),
                    details     = o.optString("details")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun addHistory(ctx: Context, entry: HistoryEntry) {
        val current = getHistory(ctx).toMutableList()
        current.add(entry)
        // Keep latest MAX_HISTORY entries
        val trimmed = if (current.size > MAX_HISTORY) current.takeLast(MAX_HISTORY) else current
        val arr = JSONArray()
        trimmed.forEach { e ->
            arr.put(JSONObject().apply {
                put("type", e.type)
                put("id",   e.serviceId)
                put("name", e.serviceName)
                put("ts",   e.timestamp)
                put("details", e.details)
            })
        }
        p(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory(ctx: Context) {
        p(ctx).edit().remove(KEY_HISTORY).apply()
    }
}
