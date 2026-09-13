package com.habibul.embassymonitor

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.habibul.embassymonitor.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingUpdate: UpdateInfo? = null
    private val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createChannels(this)
        askNotifPermission()
        setupBottomNav()
        bindButtons()
        showDashboard()
    }

    override fun onResume() {
        super.onResume()
        refreshCurrentTab()
        checkForUpdate()
    }

    // ── Permission ────────────────────────────────────────────────────────────

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startMonitor()
    }

    private fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitor()
        }
    }

    // ── Monitor ───────────────────────────────────────────────────────────────

    private fun startMonitor() {
        val req = OneTimeWorkRequestBuilder<EmbassyWorker>()
            .setConstraints(netOk()).build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork(EmbassyWorker.WORK_NAME, ExistingWorkPolicy.KEEP, req)
    }

    private fun restartMonitor() {
        WorkManager.getInstance(this).cancelUniqueWork(EmbassyWorker.CHAIN_NAME)
        startMonitor()
    }

    private fun netOk() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    // ── Bottom navigation ─────────────────────────────────────────────────────

    private var currentTab = R.id.navDashboard

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            currentTab = item.itemId
            when (item.itemId) {
                R.id.navDashboard   -> showDashboard()
                R.id.navPredictions -> showPredictions()
                R.id.navHistory     -> showHistory()
            }
            true
        }
    }

    private fun refreshCurrentTab() {
        when (currentTab) {
            R.id.navDashboard   -> showDashboard()
            R.id.navPredictions -> showPredictions()
            R.id.navHistory     -> showHistory()
        }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun bindButtons() {
        binding.ivSettings.setOnClickListener  { openSettings() }
        binding.ivUpdateBadge.setOnClickListener { showUpdateDialog() }
        binding.btnCheckNow.setOnClickListener  { manualCheck() }
        binding.btnOpenSite.setOnClickListener  {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EmbassyWorker.TARGET_URL)))
        }
        binding.btnBattery.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
        }
        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("ইতিহাস মুছবেন?")
                .setMessage("সব পরিবর্তনের রেকর্ড মুছে যাবে।")
                .setPositiveButton("মুছুন") { _, _ ->
                    PrefsHelper.clearHistory(this)
                    showHistory()
                }
                .setNegativeButton("বাতিল", null)
                .show()
        }
    }

    private fun manualCheck() {
        binding.btnCheckNow.isEnabled = false
        binding.tvMonitorStatus.text  = "⏳ চেক করছি..."
        val req = OneTimeWorkRequestBuilder<EmbassyWorker>()
            .setConstraints(netOk()).build()
        WorkManager.getInstance(this).enqueue(req)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(req.id)
            .observe(this) { info ->
                if (info?.state?.isFinished == true) {
                    binding.btnCheckNow.isEnabled = true
                    refreshCurrentTab()
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ── TAB 1: Dashboard ──────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────

    private fun showDashboard() {
        binding.screenDashboard.visibility   = View.VISIBLE
        binding.screenPredictions.visibility = View.GONE
        binding.screenHistory.visibility     = View.GONE

        val known     = PrefsHelper.getKnownOptions(this)
        val lastCheck = PrefsHelper.getLastCheckTime(this)
        val interval  = SettingsHelper.getInterval(this)
        val pm        = getSystemService(POWER_SERVICE) as PowerManager

        binding.tvMonitorStatus.text = "🟢 মনিটরিং চলছে — প্রতি ${interval} মিনিটে"

        binding.tvLastCheck.text = if (lastCheck == 0L)
            "শেষ চেক: এখনো হয়নি"
        else
            "শেষ চেক: ${fmt.format(Date(lastCheck))}"

        binding.tvServiceList.text = if (known.isEmpty())
            "এখনো কোনো service লোড হয়নি।\n'এখনই চেক করুন' চাপুন।"
        else
            known.sorted().joinToString("\n") { "• $it" }

        binding.tvServiceCount.text = if (known.isEmpty()) "" else "${known.size}টি"

        val batterySafe = pm.isIgnoringBatteryOptimizations(packageName)
        binding.btnBattery.text      = if (batterySafe) "✅ ব্যাটারি অপটিমাইজেশন বন্ধ" else "⚡ ব্যাটারি অপটিমাইজেশন বন্ধ করুন"
        binding.btnBattery.isEnabled = !batterySafe

        // Update badge
        binding.ivUpdateBadge.visibility =
            if (pendingUpdate != null) View.VISIBLE else View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ── TAB 2: Predictions ────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPredictions() {
        binding.screenDashboard.visibility   = View.GONE
        binding.screenPredictions.visibility = View.VISIBLE
        binding.screenHistory.visibility     = View.GONE

        val state = PrefsHelper.getServiceState(this)
        val ids   = state.keys.mapNotNull { it.toIntOrNull() }.toSet()

        if (ids.isEmpty()) {
            binding.tvPredictionInfo.text = "এখনো data নেই। প্রথমে Dashboard থেকে চেক করুন।"
            binding.predictionListContainer.removeAllViews()
            binding.tvCurrentIds.text = "—"
            return
        }

        val sorted = ids.sorted()
        val maxId  = sorted.last()
        val minId  = sorted.first()

        // Current IDs display
        binding.tvCurrentIds.text = sorted.joinToString(", ") { it.toString() }

        // Info text
        val gaps = ((minId..maxId).toSet() - ids)
        binding.tvPredictionInfo.text = buildString {
            append("বর্তমান Service IDs: $minId–$maxId\n")
            append("মোট active সেবা: ${ids.size}টি\n")
            if (gaps.isNotEmpty()) {
                append("Gap (missing IDs): ${gaps.sortedDescending().joinToString(", ")}\n")
            }
            append("পরবর্তী সম্ভাব্য ID: ${maxId + 1}")
        }

        // Prediction list
        val predictions = buildPredictions(ids, maxId, gaps)
        renderPredictions(predictions)
    }

    data class Prediction(
        val id         : Int,
        val confidence : Int,   // 0–100
        val label      : String,
        val reason     : String
    )

    private fun buildPredictions(
        ids: Set<Int>, maxId: Int, gaps: Set<Int>
    ): List<Prediction> {
        val list = mutableListOf<Prediction>()

        // Next sequential — highest chance (history shows services added sequentially)
        list.add(Prediction(maxId + 1, 92, "খুব সম্ভাব্য ⭐⭐⭐⭐⭐",
            "services সাধারণত পরের sequential ID-তে যোগ হয়"))

        // Second next sequential
        list.add(Prediction(maxId + 2, 60, "সম্ভাব্য ⭐⭐⭐",
            "দ্বিতীয় পরের sequential ID"))

        // Gaps — sorted by closeness to max
        for (gap in gaps.sortedDescending()) {
            val dist = maxId - gap
            val conf = when {
                dist == 1 -> 75
                dist <= 3 -> 55
                dist <= 6 -> 38
                else      -> 22
            }
            val label = when {
                conf >= 70 -> "সম্ভাব্য ⭐⭐⭐⭐"
                conf >= 50 -> "মাঝারি সম্ভাব্য ⭐⭐⭐"
                conf >= 35 -> "কম সম্ভাব্য ⭐⭐"
                else       -> "অনিশ্চিত ⭐"
            }
            list.add(Prediction(gap, conf, label, "gap — বাদ পড়া বা পরিকল্পিত ID"))
        }

        return list.sortedByDescending { it.confidence }
    }

    private fun renderPredictions(list: List<Prediction>) {
        val container = binding.predictionListContainer
        container.removeAllViews()

        list.forEach { p ->
            val row = LinearLayout(this).apply {
                orientation  = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, dp(2))
                layoutParams = lp
                setBackgroundColor(Color.WHITE)
            }

            // Header row: ID + label
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text      = "ID ${p.id}"
                textSize  = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#0f7a5a"))
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            })
            header.addView(TextView(this).apply {
                text     = "${p.confidence}%  ${p.label}"
                textSize = 12f
                setTextColor(Color.parseColor("#555555"))
            })
            row.addView(header)

            // Progress bar
            row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max      = 100
                progress = p.confidence
                val lp   = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
                )
                lp.setMargins(0, dp(6), 0, dp(4))
                layoutParams = lp
                progressTintList = android.content.res.ColorStateList.valueOf(
                    when {
                        p.confidence >= 75 -> Color.parseColor("#0f7a5a")
                        p.confidence >= 50 -> Color.parseColor("#F9A825")
                        else               -> Color.parseColor("#9E9E9E")
                    }
                )
            })

            // Reason
            row.addView(TextView(this).apply {
                text     = p.reason
                textSize = 11f
                setTextColor(Color.parseColor("#888888"))
            })

            container.addView(row)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ── TAB 3: History ────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────

    private fun showHistory() {
        binding.screenDashboard.visibility   = View.GONE
        binding.screenPredictions.visibility = View.GONE
        binding.screenHistory.visibility     = View.VISIBLE

        val history = PrefsHelper.getHistory(this)
        val container = binding.historyContainer
        container.removeAllViews()

        if (history.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "এখনো কোনো পরিবর্তন ধরা পড়েনি।\n\nApp চালু থাকলে সব change এখানে দেখা যাবে।"
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
                setPadding(dp(16), dp(24), dp(16), dp(16))
            })
            return
        }

        // Show newest first
        history.reversed().forEach { entry ->
            val icon = when (entry.type) {
                "NEW"     -> "🆕"
                "CHANGED" -> "⚠️"
                "REMOVED" -> "🗑️"
                else      -> "ℹ️"
            }
            val bgColor = when (entry.type) {
                "NEW"     -> Color.parseColor("#E8F5E9")
                "CHANGED" -> Color.parseColor("#FFF8E1")
                "REMOVED" -> Color.parseColor("#FFEBEE")
                else      -> Color.WHITE
            }

            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundColor(bgColor)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, dp(3))
                layoutParams = lp

                addView(TextView(context).apply {
                    text = "$icon  ${entry.serviceName}"
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#222222"))
                })
                addView(TextView(context).apply {
                    text = "    ${fmt.format(Date(entry.timestamp))}" +
                           if (entry.details.isNotBlank()) "\n    ${entry.details}" else ""
                    textSize = 12f
                    setTextColor(Color.parseColor("#777777"))
                    lineSpacingMultiplier = 1.4f
                })
            })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ── Settings bottom sheet ─────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────

    private fun openSettings() {
        val sheet = BottomSheetDialog(this, R.style.BottomSheetTheme)
        sheet.setContentView(R.layout.bottom_sheet_settings)

        // Interval
        val rg = sheet.findViewById<RadioGroup>(R.id.rgInterval)!!
        when (SettingsHelper.getInterval(this)) {
            5L  -> rg.check(R.id.rb5min)
            10L -> rg.check(R.id.rb10min)
            15L -> rg.check(R.id.rb15min)
            30L -> rg.check(R.id.rb30min)
            else -> rg.check(R.id.rb5min)
        }

        // Toggles
        val swSound   = sheet.findViewById<SwitchMaterial>(R.id.swSound)!!
        val swVib     = sheet.findViewById<SwitchMaterial>(R.id.swVibration)!!
        val swChanged = sheet.findViewById<SwitchMaterial>(R.id.swNotifyChanged)!!
        val swRemoved = sheet.findViewById<SwitchMaterial>(R.id.swNotifyRemoved)!!
        swSound.isChecked   = SettingsHelper.isSoundEnabled(this)
        swVib.isChecked     = SettingsHelper.isVibrationEnabled(this)
        swChanged.isChecked = SettingsHelper.notifyChanged(this)
        swRemoved.isChecked = SettingsHelper.notifyRemoved(this)

        // App version
        sheet.findViewById<TextView>(R.id.tvAppVersion)?.text =
            "Embassy Monitor v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        // Test notification
        sheet.findViewById<Button>(R.id.btnTestNotif)!!.setOnClickListener {
            NotificationHelper.notifyNewService(
                this,
                "পাসপোর্ট এনরোলমেন্ট (ভ্রাম্যমান - টেস্ট)",
                "TEST"
            )
            Toast.makeText(this, "✅ টেস্ট নোটিফিকেশন পাঠানো হয়েছে", Toast.LENGTH_SHORT).show()
        }

        // Check update button inside settings
        sheet.findViewById<Button>(R.id.btnCheckUpdate)!!.setOnClickListener {
            sheet.dismiss()
            lifecycleScope.launch {
                checkForUpdateManual()
            }
        }

        // Save
        sheet.findViewById<Button>(R.id.btnSaveSettings)!!.setOnClickListener {
            val newInterval = when (rg.checkedRadioButtonId) {
                R.id.rb5min  -> 5L
                R.id.rb10min -> 10L
                R.id.rb15min -> 15L
                R.id.rb30min -> 30L
                else -> 5L
            }
            SettingsHelper.setInterval(this, newInterval)
            SettingsHelper.setSoundEnabled(this, swSound.isChecked)
            SettingsHelper.setVibrationEnabled(this, swVib.isChecked)
            SettingsHelper.setNotifyChanged(this, swChanged.isChecked)
            SettingsHelper.setNotifyRemoved(this, swRemoved.isChecked)
            restartMonitor()
            sheet.dismiss()
            refreshCurrentTab()
            Toast.makeText(this, "✅ সেটিংস সেভ হয়েছে", Toast.LENGTH_SHORT).show()
        }

        sheet.show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ── Update check ──────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = UpdateChecker.check(BuildConfig.VERSION_CODE)
            pendingUpdate = info
            if (info != null) {
                binding.ivUpdateBadge.visibility = View.VISIBLE
                NotificationHelper.notifyUpdate(this@MainActivity, info.versionName, info.releaseNotes)
            }
        }
    }

    private suspend fun checkForUpdateManual() {
        Toast.makeText(this, "⏳ আপডেট চেক হচ্ছে...", Toast.LENGTH_SHORT).show()
        val info = UpdateChecker.check(BuildConfig.VERSION_CODE)
        pendingUpdate = info
        if (info != null) {
            binding.ivUpdateBadge.visibility = View.VISIBLE
            showUpdateDialog()
        } else {
            Toast.makeText(this, "✅ App সর্বশেষ version-এ আছে", Toast.LENGTH_LONG).show()
        }
    }

    private fun showUpdateDialog() {
        val info = pendingUpdate ?: return
        AlertDialog.Builder(this)
            .setTitle("🔄 নতুন আপডেট পাওয়া গেছে!")
            .setMessage(
                "নতুন version: ${info.versionName}\n\n" +
                "পরিবর্তন:\n${info.releaseNotes}\n\n" +
                "Download করলে system notification-এ install option আসবে।"
            )
            .setPositiveButton("⬇️ Download করুন") { _, _ ->
                UpdateChecker.downloadApk(this, info)
                Toast.makeText(this, "Download শুরু হয়েছে — Notification টানলে দেখবেন", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("পরে", null)
            .show()
    }

    // ── Util ──────────────────────────────────────────────────────────────────
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
