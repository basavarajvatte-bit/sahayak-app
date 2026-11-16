package com.sahayak.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Kisi bhi share ko dummy fund mein kharidne ka chhota dabba: kaunsa fund, kitne rupaye. */
object BuyDialog {
    private const val NEW = "+ Naya fund banao"

    /** done = jawab (screen par dikhane ke liye), main thread par. */
    fun show(a: Activity, symbol: String, name: String, slug: String, done: (String) -> Unit) {
        val d = a.resources.displayMetrics.density
        val page = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        page.addView(TextView(a).apply {
            text = "Kis fund mein?"
            textSize = 15f
        })
        val funds = Funds.all(a)
        val names = funds.map { it.optString("name") } + NEW
        val pick = Spinner(a).apply { adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, names) }
        page.addView(pick)
        val fundName = EditText(a).apply {
            hint = "Naye fund ka naam"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            visibility = if (funds.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
        page.addView(fundName)
        pick.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, i: Int, id: Long) {
                fundName.visibility = if (names[i] == NEW) android.view.View.VISIBLE else android.view.View.GONE
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }
        page.addView(TextView(a).apply {
            text = "Kitne rupaye ka?"
            textSize = 15f
            setPadding(0, (10 * d).toInt(), 0, 0)
        })
        val amount = EditText(a).apply {
            hint = "Jaise 10000"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        page.addView(amount)
        page.addView(TextView(a).apply {
            text = "Aaj ke jaanche hue bhaav par, sirf poore share. Nakli paisa."
            textSize = 12f
            setTextColor(0xFF667085.toInt())
        })
        val think = thinking(a, page)
        val dialog = AlertDialog.Builder(a).setTitle("🛒 Buy: $name").setView(android.widget.ScrollView(a).apply { addView(page) })
            .setPositiveButton("Kharido", null)
            .setNegativeButton("Rehne do", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val problem = think.problem()
                if (problem != null) {
                    android.widget.Toast.makeText(a, Lang.t(problem), android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                val rs = amount.text.toString().toDoubleOrNull() ?: 0.0
                val i = pick.selectedItemPosition.coerceIn(0, names.lastIndex)
                val app = a.applicationContext
                val main = Handler(Looper.getMainLooper())
                val newName = fundName.text.toString()
                done("$name kharid raha hu")
                Thread {
                    val answer = try {
                        var fundId = funds.getOrNull(i)?.optString("id").orEmpty()
                        if (names[i] == NEW) {
                            val made = Funds.create(app, newName)
                            fundId = Funds.all(app).firstOrNull { it.optString("name").equals(newName.trim(), true) }?.optString("id").orEmpty()
                            if (fundId.isEmpty()) made else Funds.buySymbol(app, fundId, symbol, name, slug, rs, think.thought())
                        } else Funds.buySymbol(app, fundId, symbol, name, slug, rs, think.thought())
                    } catch (e: org.json.JSONException) {
                        "Kuch gadbad hui. Dobara try karo"
                    }
                    main.post { if (!a.isDestroyed) done(answer) }
                }.start()
            }
        }
        dialog.show()
        Lang.watch(dialog)
    }

    /** Munger ki checklist ke dabbe: samajhta hu?, kyun?, kya galat ho sakta hai? */
    class Thinking(private val understand: android.widget.CheckBox, private val why: EditText, private val risks: EditText) {
        /** Sab optional hai: kuch bhi na likho to bhi kharid hoga. */
        fun problem(): String? = null

        fun thought() = Funds.Thought(why.text.toString(), risks.text.toString(), understand.isChecked)
    }

    fun thinking(a: Activity, page: LinearLayout): Thinking {
        val d = a.resources.displayMetrics.density
        page.addView(TextView(a).apply {
            text = "🧠 Khareedne se pehle socho (optional)"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (14 * d).toInt(), 0, 0)
        })
        val understand = android.widget.CheckBox(a).apply { text = "Main ye dhandha samajhta hu (kaise kamati hai)" }
        page.addView(understand)
        val why = EditText(a).apply {
            hint = "Kyun le raha hu? (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        page.addView(why)
        val risks = EditText(a).apply {
            hint = "Kya galat ho sakta hai? (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        page.addView(risks)
        page.addView(TextView(a).apply {
            text = "Khaali chhod sakte ho. Likhoge to diary mein save hoga, aur 3, 6, 12 mahine baad app poochega ki kaaran sahi nikla ya nahi."
            textSize = 12f
            setTextColor(0xFF667085.toInt())
        })
        return Thinking(understand, why, risks)
    }
}
