package com.habibul.embassymonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {

    private const val CH_NEW     = "ch_new_service_v3"
    private const val CH_CHANGED = "ch_changed_v3"
    private const val CH_UPDATE  = "ch_app_update_v3"

    private val counter = AtomicInteger(5000)

    fun createChannels(ctx: Context) {
        val nm   = ctx.getSystemService(NotificationManager::class.java)
        val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(NotificationChannel(
            CH_NEW, "নতুন সেবা", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "নতুন embassy service যোগ হলে alarm বাজবে"
            setSound(alarm, attr)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600)
        })

        nm.createNotificationChannel(NotificationChannel(
            CH_CHANGED, "সেবা পরিবর্তন", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "কোনো service-এর তথ্য বদলালে notify করবে"
            setSound(alarm, attr)
            enableVibration(true)
        })

        nm.createNotificationChannel(NotificationChannel(
            CH_UPDATE, "App আপডেট", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "নতুন app version available হলে" })
    }

    // ── New service ───────────────────────────────────────────────────────────
    fun notifyNewService(ctx: Context, name: String, prefix: String) {
        val sound = if (SettingsHelper.isSoundEnabled(ctx))
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) else null
        val vib = if (SettingsHelper.isVibrationEnabled(ctx))
            longArrayOf(0, 600, 200, 600, 200, 600) else longArrayOf(0)

        send(ctx, CH_NEW,
            title    = "🆕 নতুন সেবা যোগ হয়েছে!",
            body     = name,
            bigText  = "নতুন সেবা:\n$name\n\n" +
                       (if (prefix.isNotBlank()) "Queue prefix: $prefix\n\n" else "") +
                       "এখনই বুকিং করতে ট্যাপ করুন ↗",
            priority = NotificationCompat.PRIORITY_MAX,
            sound    = sound,
            vib      = vib,
            openUrl  = EmbassyWorker.TARGET_URL
        )
    }

    // ── Service changed / removed ─────────────────────────────────────────────
    fun notifyChanged(ctx: Context, title: String, body: String) {
        send(ctx, CH_CHANGED,
            title    = title,
            body     = body,
            bigText  = body,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            sound    = null,
            vib      = longArrayOf(0, 400),
            openUrl  = EmbassyWorker.TARGET_URL
        )
    }

    // ── App update available ──────────────────────────────────────────────────
    fun notifyUpdate(ctx: Context, versionName: String, releaseNotes: String) {
        send(ctx, CH_UPDATE,
            title    = "🔄 Embassy Monitor আপডেট পাওয়া গেছে",
            body     = "Version $versionName এখন available",
            bigText  = "Version $versionName\n\n$releaseNotes\n\nApp খুলে Settings → Update দেখুন",
            priority = NotificationCompat.PRIORITY_DEFAULT,
            sound    = null,
            vib      = longArrayOf(0, 300),
            openUrl  = null
        )
    }

    // ── Core sender ───────────────────────────────────────────────────────────
    private fun send(
        ctx: Context, channel: String,
        title: String, body: String, bigText: String,
        priority: Int, sound: Uri?, vib: LongArray,
        openUrl: String?
    ) {
        val pi = openUrl?.let {
            PendingIntent.getActivity(
                ctx, counter.get(),
                Intent(Intent.ACTION_VIEW, Uri.parse(it)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(priority)
            .setAutoCancel(true)
            .setSound(sound)
            .setVibrate(vib)
            .apply { if (pi != null) setContentIntent(pi) }
            .apply { if (openUrl != null) addAction(android.R.drawable.ic_menu_compass, "বুকিং সাইট", pi) }
            .build()

        ctx.getSystemService(NotificationManager::class.java)
            .notify(counter.getAndIncrement(), n)
    }
}
