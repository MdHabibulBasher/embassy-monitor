package com.habibul.embassymonitor

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode : Int,
    val versionName : String,
    val apkUrl      : String,
    val releaseNotes: String
)

object UpdateChecker {

    // Raw GitHub URL — always points to main branch
    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/MdHabibulBasher/embassy-monitor/main/version.json"

    /**
     * Returns UpdateInfo if a newer version is available, null otherwise.
     * Must be called from a coroutine (uses IO dispatcher internally).
     */
    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(VERSION_JSON_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.connect()

            if (conn.responseCode != 200) return@withContext null

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)

            val remoteCode = json.getInt("version_code")
            if (remoteCode <= currentVersionCode) return@withContext null

            UpdateInfo(
                versionCode  = remoteCode,
                versionName  = json.getString("version_name"),
                apkUrl       = json.getString("apk_url"),
                releaseNotes = json.optString("release_notes", "নতুন আপডেট")
            )
        } catch (e: Exception) {
            null  // Network error, server down, etc. — fail silently
        }
    }

    /**
     * Enqueue download via DownloadManager.
     * System shows notification; user taps it → install dialog appears.
     */
    fun downloadApk(ctx: Context, info: UpdateInfo) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val req = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("Embassy Monitor ${info.versionName}")
            setDescription("আপডেট ডাউনলোড হচ্ছে...")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "embassy-monitor-update.apk"
            )
        }
        dm.enqueue(req)
    }
}
