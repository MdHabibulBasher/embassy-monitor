package com.habibul.embassymonitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class BookingWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME   = "name"
        const val EXTRA_MOBILE = "mobile"
        const val EXTRA_OID    = "oid"
        const val EXTRA_LFT    = "lft"
        const val EXTRA_LABEL  = "label"

        fun start(ctx: Context, profile: BookingProfile) {
            ctx.startActivity(Intent(ctx, BookingWebViewActivity::class.java).apply {
                putExtra(EXTRA_LABEL,  profile.label)
                putExtra(EXTRA_NAME,   profile.name)
                putExtra(EXTRA_MOBILE, profile.mobile)
                putExtra(EXTRA_OID,    profile.oid)
                putExtra(EXTRA_LFT,    profile.lft)
            })
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private val name   by lazy { intent.getStringExtra(EXTRA_NAME)   ?: "" }
    private val mobile by lazy { intent.getStringExtra(EXTRA_MOBILE) ?: "" }
    private val oid    by lazy { intent.getStringExtra(EXTRA_OID)    ?: "" }
    private val lft    by lazy { intent.getStringExtra(EXTRA_LFT)    ?: "" }
    private val label  by lazy { intent.getStringExtra(EXTRA_LABEL)  ?: "" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build layout programmatically (no extra layout file needed)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Toolbar
        val toolbar = android.widget.LinearLayout(this).apply {
            orientation  = android.widget.LinearLayout.HORIZONTAL
            gravity      = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0f7a5a"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val backBtn = android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            val lp = android.widget.LinearLayout.LayoutParams(dp(28), dp(28))
            lp.setMargins(0, 0, dp(12), 0)
            layoutParams = lp
            setOnClickListener { finish() }
        }
        val titleTv = android.widget.TextView(this).apply {
            text     = "🚀 $label — বুকিং"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val statusTv = android.widget.TextView(this).apply {
            text     = "⏳ লোড হচ্ছে..."
            textSize = 11f
            setTextColor(Color.parseColor("#A8D5C8"))
        }
        toolbar.addView(backBtn)
        toolbar.addView(titleTv)
        toolbar.addView(statusTv)

        // Progress bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max           = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD600"))
            layoutParams  = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            )
        }

        // Info bar
        val infoBar = android.widget.TextView(this).apply {
            text = "📝 $name | 📱 $mobile\nService, তারিখ ও সময় নিজে select করুন।"
            textSize = 12f
            setTextColor(Color.parseColor("#444444"))
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setLineSpacing(0f, 1.4f)
        }

        // WebView
        webView = WebView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.apply {
                javaScriptEnabled      = true
                domStorageEnabled      = true
                loadWithOverviewMode   = true
                useWideViewPort        = true
                builtInZoomControls    = true
                displayZoomControls    = false
                setSupportZoom(true)
                // Match Chrome Android — avoids Cloudflare block
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.6367.82 Mobile Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                    statusTv.text = "⏳ লোড হচ্ছে..."
                }
                override fun onPageFinished(view: WebView, url: String) {
                    progressBar.visibility = View.GONE
                    if (url.contains("express-application")) {
                        injectFillScript(view)
                        statusTv.text = "✅ ফর্ম পূরণ হয়েছে"
                    } else {
                        statusTv.text = "🌐 ${url.take(40)}"
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        statusTv.text = "❌ Error — Internet চেক করুন"
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar.progress = newProgress
                }
            }

            loadUrl(EmbassyWorker.TARGET_URL)
        }

        root.addView(toolbar)
        root.addView(progressBar)
        root.addView(infoBar)
        root.addView(webView)
        setContentView(root)
    }

    // ── JS injection ──────────────────────────────────────────────────────────

    private fun injectFillScript(view: WebView) {
        val escaped = mapOf(
            "NAME"   to name.escapeJs(),
            "MOBILE" to mobile.escapeJs(),
            "OID"    to oid.escapeJs(),
            "LFT"    to lft.escapeJs()
        )

        val js = """
(function() {
    /* Vue/React native setter */
    function fill(el, val) {
        if (!el || !val) return;
        var proto  = Object.getPrototypeOf(el);
        var setter = Object.getOwnPropertyDescriptor(proto, 'value');
        if (setter && setter.set) setter.set.call(el, val);
        else el.value = val;
        el.dispatchEvent(new Event('input',  { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
    }

    /* Fill confirmed fields immediately (HAR-confirmed IDs) */
    fill(document.getElementById('full_name'),             '${escaped["NAME"]}');
    fill(document.getElementById('phone_number'),          '${escaped["MOBILE"]}');
    fill(document.getElementById('transaction_reference'), '${escaped["LFT"]}');

    /* Watch for OID — appears after service selection */
    var oidFilled = false;
    var observer = new MutationObserver(function() {
        if (oidFilled) return;
        var container = document.getElementById('custom_fields_container')
                     || document.getElementById('custom_fields_row');
        if (!container) return;
        var inputs = container.querySelectorAll('input, textarea');
        for (var i = 0; i < inputs.length; i++) {
            var inp = inputs[i];
            var id  = (inp.id   || '').toLowerCase();
            var nm  = (inp.name || '').toLowerCase();
            if (id.indexOf('oid') >= 0 || nm.indexOf('oid') >= 0 ||
                id.indexOf('contact') >= 0 || nm.indexOf('contact') >= 0) {
                if (!inp.dataset.filled) {
                    inp.dataset.filled = '1';
                    fill(inp, '${escaped["OID"]}');
                    oidFilled = true;
                }
            }
        }
        /* Fallback: first unlabeled text input in container */
        if (!oidFilled) {
            var first = container.querySelector('input[type="text"]:not([data-filled]), input:not([type]):not([data-filled])');
            if (first) {
                first.dataset.filled = '1';
                fill(first, '${escaped["OID"]}');
                oidFilled = true;
            }
        }
        /* Also re-fill LFT if it appeared late */
        var lft = document.getElementById('transaction_reference');
        if (lft && !lft.value) fill(lft, '${escaped["LFT"]}');
    });
    observer.observe(document.body, { childList: true, subtree: true });
})();
""".trimIndent()

        view.evaluateJavascript(js) { result ->
            if (result == "null" || result == null) {
                Toast.makeText(this, "✅ Form fill হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun String.escapeJs() = this
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
