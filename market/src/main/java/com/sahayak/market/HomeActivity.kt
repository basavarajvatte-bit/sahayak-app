package com.sahayak.market

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sahayak.app.FundsActivity
import com.sahayak.app.Market
import com.sahayak.app.MarketActivity
import com.sahayak.app.PatternActivity
import com.sahayak.app.SecureStore
import com.sahayak.app.WatchActivity

/** Market Watch app ki pehli screen: kahan jaana hai, aur ek baar ki setup. */
class HomeActivity : Activity() {
    private lateinit var keyLine: TextView
    private lateinit var batteryButton: Button
    private val store by lazy { SecureStore(getSharedPreferences("sahayak", Context.MODE_PRIVATE)) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(words: String, size: Float, bold: Boolean = false, color: Int = INK) = TextView(this).apply {
        text = words
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(3))
    }

    private fun button(words: String, action: () -> Unit) = Button(this).apply {
        text = words
        isAllCaps = false
        textSize = 16f
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F6FA.toInt())
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(page) })
        page.addView(text("📈 Market Watch", 26f, true))
        page.addView(text("Bazaar ki khabar, apne shares, pattern study aur nakli portfolio. Seekhne ke liye, salah nahi.", 14f, false, GREY))

        page.addView(button("📈 Market Watch: news, my shares, green scorecard") { startActivity(Intent(this, MarketActivity::class.java)) })
        page.addView(button("💼 My portfolio: dummy funds") { startActivity(Intent(this, FundsActivity::class.java)) })
        page.addView(button("📊 Pattern study: dividend, split, buyback") { startActivity(Intent(this, PatternActivity::class.java)) })
        page.addView(button("🔔 News Watch: kisi bhi khabar par nazar") { startActivity(Intent(this, WatchActivity::class.java)) })

        page.addView(text("Setup (ek baar)", 18f, true).apply { setPadding(0, dp(20), 0, dp(4)) })
        page.addView(text(
            "Gemini key daaloge to khabar ki Depth research aur AI jaanch chalegi. Bina key ke baaki sab chalega. " +
                "Key free milti hai aistudio.google.com par. Ye key sirf aapke phone mein rehti hai, kisi ko mat bhejna.", 13f, false, GREY
        ))
        val key = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        page.addView(key)
        page.addView(button("Key save karo") {
            val k = key.text.toString().trim()
            if (k.isNotEmpty()) {
                store.save(k)
                key.setText("")
            }
            render()
        })
        keyLine = text("", 13f, true, TEAL)
        page.addView(keyLine)
        batteryButton = button("") {
            if (!batteryFree()) {
                @SuppressLint("BatteryLife")
                val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(ask)
            }
        }
        page.addView(batteryButton)
        page.addView(text("Background chalu rakhoge to roz ki report, har ghante ki bhaav jaanch aur khabar ke alert time par aayenge.", 12f, false, GREY))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Market.rearm(this)
    }

    private fun batteryFree() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        keyLine.text = if (store.load().isNotEmpty()) "✓ Gemini key saved" else "Gemini key abhi nahi hai (zaroori nahi)"
        batteryButton.text = if (batteryFree()) "✓ Background chalu hai" else "Background mein chalne do"
    }

    companion object {
        private const val INK = 0xFF1D2433.toInt()
        private const val GREY = 0xFF667085.toInt()
        private const val TEAL = 0xFF1F5C4D.toInt()
    }
}
