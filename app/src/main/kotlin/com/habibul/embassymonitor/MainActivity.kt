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
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.habibul.embassymonitor.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingUpdate: UpdateInfo? = null
    private val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

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
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startMonitor() }

    private fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startMonitor()
    }

    // ── Monitor ───────────────────────────────────────────────────────────────
    private fun startMonitor() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            EmbassyWorker.WORK_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EmbassyWorker>().setConstraints(netOk()).build()
        )
    }
    private fun restartMonitor() {
        WorkManager.getInstance(this).cancelUniqueWork(EmbassyWorker.CHAIN_NAME)
        startMonitor()
    }
    private fun netOk() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    // ── Bottom nav ────────────────────────────────────────────────────────────
    private var currentTab = R.id.navDashboard

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            currentTab = item.itemId
            when (item.itemId) {
                R.id.navDashboard   -> showDashboard()
                R.id.navPredictions -> showPredictions()
                R.id.navHistory     -> showHistory()
                R.id.navBooking     -> showBooking()
            }
            true
        }
    }

    private fun refreshCurrentTab() {
        when (currentTab) {
            R.id.navDashboard   -> showDashboard()
            R.id.navPredictions -> showPredictions()
            R.id.navHistory     -> showHistory()
            R.id.navBooking     -> showBooking()
        }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────
    private fun bindButtons() {
        binding.ivSettings.setOnClickListener    { openSettings() }
        binding.ivUpdateBadge.setOnClickListener { showUpdateDialog() }
        binding.btnCheckNow.setOnClickListener   { manualCheck() }
        binding.btnOpenSite.setOnClickListener   {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EmbassyWorker.TARGET_URL)))
        }
        binding.btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this).setTitle("ইতিহাস মুছবেন?")
                .setMessage("সব পরিবর্তনের রেকর্ড মুছে যাবে।")
                .setPositiveButton("মুছুন") { _, _ -> PrefsHelper.clearHistory(this); showHistory() }
                .setNegativeButton("বাতিল", null).show()
        }
    }

    private fun manualCheck() {
        binding.btnCheckNow.isEnabled = false
        binding.tvMonitorStatus.text  = "⏳ চেক করছি..."
        val req = OneTimeWorkRequestBuilder<EmbassyWorker>().setConstraints(netOk()).build()
        WorkManager.getInstance(this).enqueue(req)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(req.id).observe(this) { info ->
            if (info?.state?.isFinished == true) {
                binding.btnCheckNow.isEnabled = true
                refreshCurrentTab()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 1: Dashboard
    // ─────────────────────────────────────────────────────────────────────────
    private fun showDashboard() {
        binding.screenDashboard.visibility   = View.VISIBLE
        binding.screenPredictions.visibility = View.GONE
        binding.screenHistory.visibility     = View.GONE
        binding.screenBooking.visibility     = View.GONE

        val known    = PrefsHelper.getKnownOptions(this)
        val lastCheck= PrefsHelper.getLastCheckTime(this)
        val pm       = getSystemService(POWER_SERVICE) as PowerManager

        binding.tvMonitorStatus.text = "🟢 মনিটরিং — প্রতি ${SettingsHelper.getInterval(this)} মিনিটে"
        binding.tvLastCheck.text = if (lastCheck == 0L) "শেষ চেক: এখনো হয়নি"
            else "শেষ চেক: ${fmt.format(Date(lastCheck))}"
        binding.tvServiceList.text = if (known.isEmpty()) "এখনো লোড হয়নি — 'এখনই চেক করুন' চাপুন।"
            else known.sorted().joinToString("\n") { "• $it" }
        binding.tvServiceCount.text = if (known.isEmpty()) "" else "${known.size}টি"

        val batterySafe = pm.isIgnoringBatteryOptimizations(packageName)
        binding.btnBattery.text      = if (batterySafe) "✅ ব্যাটারি OK" else "⚡ ব্যাটারি অপটিমাইজেশন বন্ধ করুন"
        binding.btnBattery.isEnabled = !batterySafe
        binding.ivUpdateBadge.visibility = if (pendingUpdate != null) View.VISIBLE else View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 2: Predictions
    // ─────────────────────────────────────────────────────────────────────────
    private fun showPredictions() {
        binding.screenDashboard.visibility   = View.GONE
        binding.screenPredictions.visibility = View.VISIBLE
        binding.screenHistory.visibility     = View.GONE
        binding.screenBooking.visibility     = View.GONE

        val state = PrefsHelper.getServiceState(this)
        val ids   = state.keys.mapNotNull { it.toIntOrNull() }.toSet()
        if (ids.isEmpty()) {
            binding.tvPredictionInfo.text = "Data নেই — Dashboard থেকে চেক করুন।"
            binding.predictionListContainer.removeAllViews(); binding.tvCurrentIds.text = "—"; return
        }
        val sorted = ids.sorted(); val maxId = sorted.last(); val minId = sorted.first()
        val gaps   = ((minId..maxId).toSet() - ids)
        binding.tvCurrentIds.text = sorted.joinToString(", ")
        binding.tvPredictionInfo.text = "Active: ${ids.size}টি | Max ID: $maxId | Gap: ${gaps.joinToString(", ").ifEmpty { "নেই" }}\nNext সম্ভাবনা: ID ${maxId+1}"
        renderPredictions(buildPredictions(ids, maxId, gaps))
    }

    data class Prediction(val id: Int, val confidence: Int, val label: String, val hint: String)

    private fun buildPredictions(ids: Set<Int>, maxId: Int, gaps: Set<Int>): List<Prediction> {
        val list = mutableListOf<Prediction>()
        list.add(Prediction(maxId+1, 92, "⭐⭐⭐⭐⭐ খুব সম্ভাব্য", if (maxId == 24) "সম্ভবত ভ্রাম্যমান সালালাহ (screenshot-এ দেখা গেছে)" else "পরের sequential ID"))
        list.add(Prediction(maxId+2, 60, "⭐⭐⭐ সম্ভাব্য", if (maxId == 24) "সম্ভবত পাসপোর্ট বিতরণ - ট্যুর (সালালাহ)" else "দ্বিতীয় পরের ID"))
        for (gap in gaps.sortedDescending()) {
            val dist = maxId - gap
            val conf = when { dist<=1->75; dist<=3->55; dist<=6->38; else->22 }
            val star = when { conf>=70->"⭐⭐⭐⭐"; conf>=50->"⭐⭐⭐"; conf>=35->"⭐⭐"; else->"⭐" }
            list.add(Prediction(gap, conf, "$star Gap ID", "পূর্বে এই ID-তে service ছিল"))
        }
        return list.sortedByDescending { it.confidence }
    }

    private fun renderPredictions(list: List<Prediction>) {
        val c = binding.predictionListContainer; c.removeAllViews()
        list.forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14),dp(10),dp(14),dp(10))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0,0,0,dp(2)); layoutParams = lp; setBackgroundColor(Color.WHITE)
            }
            val hdr = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
            hdr.addView(TextView(this).apply {
                text="ID ${p.id}"; textSize=15f; setTypeface(typeface,Typeface.BOLD); setTextColor(Color.parseColor("#0f7a5a"))
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            })
            hdr.addView(TextView(this).apply { text="${p.confidence}%  ${p.label}"; textSize=12f; setTextColor(Color.parseColor("#555")) })
            row.addView(hdr)
            row.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=100; progress=p.confidence
                val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(6)); lp.setMargins(0,dp(6),0,dp(4)); layoutParams=lp
                progressTintList=android.content.res.ColorStateList.valueOf(when{p.confidence>=75->Color.parseColor("#0f7a5a");p.confidence>=50->Color.parseColor("#F9A825");else->Color.parseColor("#9E9E9E")})
            })
            row.addView(TextView(this).apply { text=p.hint; textSize=11f; setTextColor(Color.parseColor("#888")) })
            c.addView(row)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 3: History
    // ─────────────────────────────────────────────────────────────────────────
    private fun showHistory() {
        binding.screenDashboard.visibility   = View.GONE
        binding.screenPredictions.visibility = View.GONE
        binding.screenHistory.visibility     = View.VISIBLE
        binding.screenBooking.visibility     = View.GONE

        val history = PrefsHelper.getHistory(this)
        val container = binding.historyContainer; container.removeAllViews()
        if (history.isEmpty()) {
            container.addView(TextView(this).apply {
                text="এখনো কোনো পরিবর্তন ধরা পড়েনি।"; textSize=14f
                setTextColor(Color.parseColor("#888")); setPadding(dp(16),dp(24),dp(16),dp(16))
            }); return
        }
        history.reversed().forEach { e ->
            val icon = when(e.type){"NEW"->"🆕";"CHANGED"->"⚠️";"REMOVED"->"🗑️";else->"ℹ️"}
            val bg   = when(e.type){"NEW"->Color.parseColor("#E8F5E9");"CHANGED"->Color.parseColor("#FFF8E1");"REMOVED"->Color.parseColor("#FFEBEE");else->Color.WHITE}
            container.addView(LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(10),dp(14),dp(10)); setBackgroundColor(bg)
                val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT); lp.setMargins(0,0,0,dp(3)); layoutParams=lp
                addView(TextView(context).apply{text="$icon  ${e.serviceName}";textSize=14f;setTypeface(typeface,Typeface.BOLD);setTextColor(Color.parseColor("#222"))})
                addView(TextView(context).apply{text="    ${fmt.format(Date(e.timestamp))}";textSize=12f;setTextColor(Color.parseColor("#777"))})
            })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TAB 4: Quick Booking
    // ─────────────────────────────────────────────────────────────────────────
    private fun showBooking() {
        binding.screenDashboard.visibility   = View.GONE
        binding.screenPredictions.visibility = View.GONE
        binding.screenHistory.visibility     = View.GONE
        binding.screenBooking.visibility     = View.VISIBLE
        renderProfiles()
    }

    private fun renderProfiles() {
        val container = binding.bookingContainer
        container.removeAllViews()
        val profiles = ProfilesHelper.getAll(this)

        if (profiles.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "কোনো profile নেই।\n\nনিচের '+' বাটন দিয়ে যোগ করুন।"
                textSize = 14f; setTextColor(Color.parseColor("#888888"))
                setPadding(dp(16), dp(24), dp(16), dp(16))
            })
        }

        profiles.forEach { profile ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, dp(8)); layoutParams = lp
                elevation = dp(1).toFloat()
            }

            // Top row: label + buttons
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = android.view.Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this).apply {
                text = "📋 ${profile.label}"
                textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#0f7a5a"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Edit button
            topRow.addView(Button(this).apply {
                text = "✏️"; textSize = 13f
                setBackgroundColor(Color.parseColor("#E8F5E9"))
                setTextColor(Color.parseColor("#0f7a5a"))
                val lp = LinearLayout.LayoutParams(dp(50), dp(36)); lp.setMargins(0,0,dp(6),0); layoutParams=lp
                setOnClickListener { openEditProfile(profile) }
            })
            // Book button
            topRow.addView(Button(this).apply {
                text = "🚀 বুকিং"
                textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#0f7a5a"))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)); layoutParams=lp
                setOnClickListener { BookingWebViewActivity.start(this@MainActivity, profile) }
            })
            card.addView(topRow)

            // Detail row
            card.addView(TextView(this).apply {
                text = "👤 ${profile.name.ifBlank { "—" }}   📱 ${profile.mobile.ifBlank { "—" }}\n" +
                       "🆔 ${profile.oid.ifBlank { "—" }}\n💳 ${profile.lft.ifBlank { "—" }}"
                textSize = 12f; setTextColor(Color.parseColor("#555555"))
                setLineSpacing(0f, 1.5f)
                setPadding(0, dp(6), 0, 0)
            })
            container.addView(card)
        }

        // Add new profile button
        if (profiles.size < ProfilesHelper.MAX_PROFILES) {
            container.addView(Button(this).apply {
                text = "+ নতুন প্রোফাইল যোগ করুন"
                textSize = 14f
                setBackgroundColor(Color.parseColor("#E3F2FD"))
                setTextColor(Color.parseColor("#1565C0"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
                ); lp.setMargins(0, dp(4), 0, 0); layoutParams = lp
                setOnClickListener {
                    openEditProfile(BookingProfile(
                        id     = ProfilesHelper.nextId(this@MainActivity),
                        label  = ProfilesHelper.nextLabel(this@MainActivity),
                        name   = "", mobile = "", oid = "", lft = ""
                    ))
                }
            })
        }
    }

    private fun openEditProfile(profile: BookingProfile) {
        val sheet = BottomSheetDialog(this, R.style.BottomSheetTheme)
        val root  = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }

        // Handle bar
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#CCCCCC"))
            val lp = LinearLayout.LayoutParams(dp(40), dp(4))
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            lp.setMargins(0, 0, 0, dp(12)); layoutParams = lp
        })

        val isNew = profile.isEmpty()
        root.addView(TextView(this).apply {
            text = if (isNew) "➕ নতুন প্রোফাইল" else "✏️ ${profile.label} সম্পাদনা"
            textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0f7a5a"))
            setPadding(0, 0, 0, dp(16))
        })

        fun field(hint: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(this).apply {
                setText(value); this.hint = hint; this.inputType = inputType
                textSize = 15f; setTextColor(Color.parseColor("#222222"))
                background = null
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, dp(12)); layoutParams = lp
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setBackgroundResource(android.R.drawable.edit_text)
            }
        }

        val etLabel  = field("লেবেল (যেমন: ফাইল ১)", profile.label)
        val etName   = field("আবেদনকারীর নাম (SALIM)", profile.name)
        val etMobile = field("মোবাইল নম্বর (71938945)", profile.mobile, InputType.TYPE_CLASS_NUMBER)
        val etOid    = field("OID নম্বর (OID1036782339)", profile.oid)
        val etLft    = field("LFT/TT রেফারেন্স (LFT262227...)", profile.lft)

        root.addView(etLabel); root.addView(etName)
        root.addView(etMobile); root.addView(etOid); root.addView(etLft)

        // Buttons row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
        }

        if (!isNew) {
            btnRow.addView(Button(this).apply {
                text = "🗑️ মুছুন"; textSize = 13f
                setBackgroundColor(Color.parseColor("#FFCDD2"))
                setTextColor(Color.parseColor("#C62828"))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48))
                lp.setMargins(0,0,dp(8),0); layoutParams = lp
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("মুছবেন?").setMessage("${profile.label} মুছে যাবে।")
                        .setPositiveButton("মুছুন") { _,_ ->
                            ProfilesHelper.delete(this@MainActivity, profile.id)
                            sheet.dismiss(); renderProfiles()
                        }.setNegativeButton("না", null).show()
                }
            })
        }

        btnRow.addView(Button(this).apply {
            text = "💾 সেভ করুন"; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#0f7a5a"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setOnClickListener {
                val updated = profile.copy(
                    label  = etLabel.text.toString().trim().ifBlank { profile.label },
                    name   = etName.text.toString().trim(),
                    mobile = etMobile.text.toString().trim(),
                    oid    = etOid.text.toString().trim(),
                    lft    = etLft.text.toString().trim()
                )
                ProfilesHelper.save(this@MainActivity, updated)
                sheet.dismiss(); renderProfiles()
            }
        })
        root.addView(btnRow)
        sheet.setContentView(root); sheet.show()
    }

    // ── Settings ──────────────────────────────────────────────────────────────
    private fun openSettings() {
        val sheet = BottomSheetDialog(this, R.style.BottomSheetTheme)
        sheet.setContentView(R.layout.bottom_sheet_settings)
        val rg = sheet.findViewById<RadioGroup>(R.id.rgInterval)!!
        when (SettingsHelper.getInterval(this)) { 5L->rg.check(R.id.rb5min);10L->rg.check(R.id.rb10min);15L->rg.check(R.id.rb15min);30L->rg.check(R.id.rb30min);else->rg.check(R.id.rb5min) }
        val swSound   = sheet.findViewById<SwitchMaterial>(R.id.swSound)!!.also{it.isChecked=SettingsHelper.isSoundEnabled(this)}
        val swVib     = sheet.findViewById<SwitchMaterial>(R.id.swVibration)!!.also{it.isChecked=SettingsHelper.isVibrationEnabled(this)}
        val swChanged = sheet.findViewById<SwitchMaterial>(R.id.swNotifyChanged)!!.also{it.isChecked=SettingsHelper.notifyChanged(this)}
        val swRemoved = sheet.findViewById<SwitchMaterial>(R.id.swNotifyRemoved)!!.also{it.isChecked=SettingsHelper.notifyRemoved(this)}
        sheet.findViewById<TextView>(R.id.tvAppVersion)?.text = "Embassy Monitor v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        sheet.findViewById<Button>(R.id.btnTestNotif)!!.setOnClickListener {
            NotificationHelper.notifyNewService(this,"পাসপোর্ট এনরোলমেন্ট ভ্রাম্যমান (টেস্ট)","PEMS")
            Toast.makeText(this,"✅ টেস্ট পাঠানো হয়েছে",Toast.LENGTH_SHORT).show()
        }
        sheet.findViewById<Button>(R.id.btnCheckUpdate)!!.setOnClickListener { sheet.dismiss(); lifecycleScope.launch{checkForUpdateManual()} }
        sheet.findViewById<Button>(R.id.btnSaveSettings)!!.setOnClickListener {
            val newInterval = when(rg.checkedRadioButtonId){R.id.rb5min->5L;R.id.rb10min->10L;R.id.rb15min->15L;R.id.rb30min->30L;else->5L}
            SettingsHelper.setInterval(this,newInterval); SettingsHelper.setSoundEnabled(this,swSound.isChecked)
            SettingsHelper.setVibrationEnabled(this,swVib.isChecked); SettingsHelper.setNotifyChanged(this,swChanged.isChecked); SettingsHelper.setNotifyRemoved(this,swRemoved.isChecked)
            restartMonitor(); sheet.dismiss(); refreshCurrentTab()
            Toast.makeText(this,"✅ সেটিংস সেভ হয়েছে",Toast.LENGTH_SHORT).show()
        }
        sheet.show()
    }

    // ── Update ────────────────────────────────────────────────────────────────
    private fun checkForUpdate() { lifecycleScope.launch { val info=UpdateChecker.check(BuildConfig.VERSION_CODE); pendingUpdate=info; if(info!=null){binding.ivUpdateBadge.visibility=View.VISIBLE;NotificationHelper.notifyUpdate(this@MainActivity,info.versionName,info.releaseNotes)} } }
    private suspend fun checkForUpdateManual() { Toast.makeText(this,"⏳ চেক হচ্ছে...",Toast.LENGTH_SHORT).show(); val info=UpdateChecker.check(BuildConfig.VERSION_CODE); pendingUpdate=info; if(info!=null){binding.ivUpdateBadge.visibility=View.VISIBLE;showUpdateDialog()}else{Toast.makeText(this,"✅ সর্বশেষ version-এ আছেন",Toast.LENGTH_LONG).show()} }
    private fun showUpdateDialog() { val info=pendingUpdate?:return; AlertDialog.Builder(this).setTitle("🔄 আপডেট পাওয়া গেছে!").setMessage("Version ${info.versionName}\n\n${info.releaseNotes}\n\nDownload করলে system notification-এ install option আসবে।").setPositiveButton("⬇️ Download"){_,_->UpdateChecker.downloadApk(this,info);Toast.makeText(this,"Download শুরু হয়েছে",Toast.LENGTH_LONG).show()}.setNegativeButton("পরে",null).show() }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
