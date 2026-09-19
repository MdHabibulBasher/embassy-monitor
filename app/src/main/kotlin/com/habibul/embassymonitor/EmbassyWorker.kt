package com.habibul.embassymonitor

import android.content.Context
import android.util.Log
import androidx.work.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class EmbassyWorker(
    private val ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    companion object {
        const val TARGET_URL  = "https://amardutabashmuscat.org/express-application"
        const val WORK_NAME   = "embassy_v3"
        const val CHAIN_NAME  = "embassy_v3_chain"
        private  const val TAG = "EmbassyWorker"

        private val SERVICE_REGEX = Regex(
            """const\s+services\s*=\s*(\[.*?]);""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
    }

    // ── Service model ─────────────────────────────────────────────────────────

    data class Service(
        val id           : Int,
        val nameEn       : String,
        val nameBn       : String,
        val queuePrefix  : String,
        val accountNumber: String,
        val bankName     : String,
        val showTxnRef   : Boolean,
    ) {
        fun display()      = nameBn.ifBlank { nameEn }

        /** MD5 fingerprint — any field change → new fingerprint */
        fun fingerprint(): String {
            val raw = "$id|$nameEn|$nameBn|$queuePrefix|$accountNumber|$bankName|$showTxnRef"
            return MessageDigest.getInstance("MD5")
                .digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    override fun doWork(): Result {
        Log.d(TAG, "doWork() start")
        return try {
            val current = fetchServices()
            if (current.isEmpty()) {
                scheduleNext(); return Result.retry()
            }

            if (!PrefsHelper.isSeeded(ctx)) {
                seed(current)
                scheduleNext(); return Result.success()
            }

            detectAndNotify(current)
            scheduleNext()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            scheduleNext()
            Result.retry()
        }
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────

    private fun fetchServices(): List<Service> {
        val html = Jsoup.connect(TARGET_URL)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0")
            .header("Cache-Control", "no-cache")
            .timeout(30_000)
            .get()
            .html()

        // Primary: const services = [...] embedded in JavaScript
        SERVICE_REGEX.find(html)?.groupValues?.get(1)?.let { json ->
            return parseJson(json).also {
                Log.d(TAG, "Parsed ${it.size} services from JS variable")
            }
        }

        // Fallback: HTML dropdown (less detail but covers edge cases)
        Log.w(TAG, "JS variable not found — using dropdown fallback")
        val soup = Jsoup.parse(html)
        for (sel in soup.select("select")) {
            val options: List<Element> = sel.select("option")
            val opts = options.map { it.text().trim() }
            if (opts.any { "পাসপোর্ট" in it }) {
                return options
                    .filter { it.attr("value").isNotBlank() && "নির্বাচন করুন" !in it.text() }
                    .mapNotNull { o ->
                        val id = o.attr("value").toIntOrNull() ?: return@mapNotNull null
                        Service(id, o.text(), o.text(), "", "", "", false)
                    }
            }
        }
        return emptyList()
    }

    private fun parseJson(jsonStr: String): List<Service> = try {
        val arr = JSONArray(jsonStr)
        (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                Service(
                    id            = o.optInt("id"),
                    nameEn        = o.optString("name_en"),
                    nameBn        = o.optString("name_bn"),
                    queuePrefix   = o.optString("queue_prefix"),
                    accountNumber = o.optString("account_number"),
                    bankName      = o.optString("bank_name"),
                    showTxnRef    = o.optBoolean("show_transaction_ref", false),
                )
            } catch (e: Exception) { null }
        }
    } catch (e: Exception) {
        Log.e(TAG, "JSON parse error: ${e.message}")
        emptyList()
    }

    // ── Seed (first run) ──────────────────────────────────────────────────────

    private fun seed(services: List<Service>) {
        val state = services.associate { it.id.toString() to it.fingerprint() }
        PrefsHelper.saveServiceState(ctx, state)
        PrefsHelper.saveKnownOptions(ctx, services.map { it.display() }.toSet())
        Log.i(TAG, "Seeded ${services.size} services: ${services.map { it.id }}")
    }

    // ── Change detection ──────────────────────────────────────────────────────

    private fun detectAndNotify(current: List<Service>) {
        val savedState  = PrefsHelper.getServiceState(ctx)
        val currentMap  = current.associateBy { it.id.toString() }
        val now         = System.currentTimeMillis()

        // ① New services
        for (svc in current) {
            val sid = svc.id.toString()
            if (sid !in savedState) {
                Log.i(TAG, "🆕 NEW [${svc.id}] ${svc.display()}")
                NotificationHelper.notifyNewService(ctx, svc.display(), svc.queuePrefix)
                PrefsHelper.addHistory(ctx, HistoryEntry(
                    type        = "NEW",
                    serviceId   = svc.id,
                    serviceName = svc.display(),
                    timestamp   = now,
                    details     = "prefix=${svc.queuePrefix} bank=${svc.accountNumber}"
                ))
            }
        }

        // ② Changed services
        if (SettingsHelper.notifyChanged(ctx)) {
            for (svc in current) {
                val sid  = svc.id.toString()
                val oldFp = savedState[sid] ?: continue
                if (oldFp != svc.fingerprint()) {
                    Log.i(TAG, "⚠️ CHANGED [${svc.id}] ${svc.display()}")
                    NotificationHelper.notifyChanged(
                        ctx,
                        "⚠️ সেবার তথ্য পরিবর্তিত হয়েছে",
                        "${svc.display()} (ID ${svc.id})"
                    )
                    PrefsHelper.addHistory(ctx, HistoryEntry(
                        type        = "CHANGED",
                        serviceId   = svc.id,
                        serviceName = svc.display(),
                        timestamp   = now
                    ))
                }
            }
        }

        // ③ Removed services
        if (SettingsHelper.notifyRemoved(ctx)) {
            for (sid in savedState.keys - currentMap.keys.toSet()) {
                Log.i(TAG, "🗑️ REMOVED [$sid]")
                NotificationHelper.notifyChanged(
                    ctx,
                    "🗑️ সেবা সরানো হয়েছে",
                    "Service ID $sid আর active নেই"
                )
                PrefsHelper.addHistory(ctx, HistoryEntry(
                    type        = "REMOVED",
                    serviceId   = sid.toIntOrNull() ?: 0,
                    serviceName = "ID $sid",
                    timestamp   = now
                ))
            }
        }

        // Save new state
        val newState = current.associate { it.id.toString() to it.fingerprint() }
        PrefsHelper.saveServiceState(ctx, newState)
        PrefsHelper.saveKnownOptions(ctx, current.map { it.display() }.toSet())
    }

    // ── Reschedule ────────────────────────────────────────────────────────────

    private fun scheduleNext() {
        val min = SettingsHelper.getInterval(ctx)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            CHAIN_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EmbassyWorker>()
                .setInitialDelay(min, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
        Log.d(TAG, "Next check in ${min}m")
    }
}
