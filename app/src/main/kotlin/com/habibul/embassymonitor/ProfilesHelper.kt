package com.habibul.embassymonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BookingProfile(
    val id    : Int,
    val label : String,   // ফাইল ১, ফাইল ২ ...
    val name  : String,
    val mobile: String,
    val oid   : String,
    val lft   : String
) {
    fun isEmpty() = name.isBlank() && mobile.isBlank() && oid.isBlank() && lft.isBlank()
}

object ProfilesHelper {

    private const val PREFS = "embassy_profiles"
    private const val KEY   = "profiles_json"
    const val MAX_PROFILES  = 5

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(ctx: Context): List<BookingProfile> {
        val raw = p(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BookingProfile(
                    id     = o.getInt("id"),
                    label  = o.getString("label"),
                    name   = o.optString("name"),
                    mobile = o.optString("mobile"),
                    oid    = o.optString("oid"),
                    lft    = o.optString("lft")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun save(ctx: Context, profile: BookingProfile) {
        val list = getAll(ctx).toMutableList()
        val idx  = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)

        val arr = JSONArray()
        list.forEach { pr ->
            arr.put(JSONObject().apply {
                put("id",     pr.id)
                put("label",  pr.label)
                put("name",   pr.name)
                put("mobile", pr.mobile)
                put("oid",    pr.oid)
                put("lft",    pr.lft)
            })
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun delete(ctx: Context, profileId: Int) {
        val list = getAll(ctx).filter { it.id != profileId }
        val arr  = JSONArray()
        list.forEach { pr ->
            arr.put(JSONObject().apply {
                put("id",     pr.id)
                put("label",  pr.label)
                put("name",   pr.name)
                put("mobile", pr.mobile)
                put("oid",    pr.oid)
                put("lft",    pr.lft)
            })
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun nextId(ctx: Context): Int =
        (getAll(ctx).maxOfOrNull { it.id } ?: 0) + 1

    fun nextLabel(ctx: Context): String {
        val count = getAll(ctx).size + 1
        return "ফাইল $count"
    }
}
