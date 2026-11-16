package com.sahayak.app

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Marathi wala Market Watch: screen par dikhne wali har line yahan se Marathi banti hai.
 * Code mein sab kuch pehle jaisa hi rehta hai; sirf dikhate waqt badalta hai. Sahayak mein ye band rehta hai.
 */
object Lang {
    /** Sirf Marathi app mein true. */
    @Volatile
    var on = false

    private const val NUM = "(?:\\d{1,2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*(?: \\d{4})?(?:, \\d{1,2}:\\d{2} [AP]M)?" +
        "|\\d{1,2}:\\d{2} [AP]M|[+\\-−]?₹?\\d[\\d,]*(?:\\.\\d+)?%?)"
    private val numbers = Regex(NUM)
    private val breaks = Regex(" +[·—] +")
    private val sentenceEnd = Regex("(?<=[.!?]) +")
    private const val MAX_SENTENCES = 12
    private const val CACHE = 3_000

    private class Template(val regex: Regex, val kinds: List<Char>, val value: String, val weight: Int)

    private val exact = HashMap<String, String>()
    private var templates: List<Template> = emptyList()
    private var loaded = false

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE
    }

    private fun normal(s: String) = numbers.replace(s, "#")

    @Synchronized
    private fun load() {
        if (loaded) return
        val list = mutableListOf<Template>()
        LangMr.PARTS.forEach { part ->
            part.lineSequence().forEach { raw ->
                val line = raw.trim()
                val cut = line.indexOf(" => ")
                if (line.isEmpty() || line.startsWith("//") || cut <= 0) return@forEach
                val key = line.substring(0, cut).trim()
                val value = line.substring(cut + 4).trim()
                if (key.contains('@')) list.add(template(normal(key), value)) else exact[normal(key)] = value
            }
        }
        templates = list.sortedByDescending { it.weight }
        loaded = true
    }

    /** "Locking @ (# of #)" jaisi line: @ = koi naam, # = koi number ya tarikh. */
    private fun template(key: String, value: String): Template {
        val pattern = StringBuilder()
        val kinds = mutableListOf<Char>()
        val plain = StringBuilder()
        var weight = 0
        fun flush() {
            if (plain.isNotEmpty()) pattern.append(Regex.escape(plain.toString()))
            weight += plain.length
            plain.setLength(0)
        }
        key.forEach { ch ->
            when (ch) {
                '@' -> { flush(); pattern.append("(.+?)"); kinds.add('@') }
                '#' -> { flush(); pattern.append("(").append(NUM).append(")"); kinds.add('#') }
                else -> plain.append(ch)
            }
        }
        flush()
        return Template(Regex(pattern.toString(), RegexOption.DOT_MATCHES_ALL), kinds, value, weight)
    }

    /** Jawab mein # aur @ ki jagah asli number aur naam bharo. {2} = doosra number, {@2} = doosra naam. */
    private fun fill(value: String, nums: List<String>, names: List<String>): String {
        var out = value
        names.forEachIndexed { i, n -> out = out.replace("{@" + (i + 1) + "}", n) }
        nums.forEachIndexed { i, n -> out = out.replace("{" + (i + 1) + "}", n) }
        val text = StringBuilder()
        var ni = 0
        var ai = 0
        out.forEach { ch ->
            when {
                ch == '#' && ni < nums.size -> text.append(nums[ni++])
                ch == '@' && ai < names.size -> text.append(names[ai++])
                else -> text.append(ch)
            }
        }
        return text.toString()
    }

    private fun lookup(x: String): String? {
        if (x.isEmpty()) return null
        exact[normal(x)]?.let { v -> return fill(v, numbers.findAll(x).map { it.value }.toList(), emptyList()) }
        for (t in templates) {
            val m = t.regex.matchEntire(x) ?: continue
            val nums = mutableListOf<String>()
            val names = mutableListOf<String>()
            t.kinds.forEachIndexed { i, kind ->
                val got = m.groupValues[i + 1]
                if (kind == '#') nums.add(got) else names.add(line(got))
            }
            return fill(t.value, nums, names)
        }
        return null
    }

    private fun starts(c: Char) = c.isLetterOrDigit() || c in "₹+-−\"(#@"

    /** Poori line, phir aage ke nishaan (✓ 🔒 •) aur peeche ke . : … hata kar dhoondho. */
    private fun whole(x: String): String? {
        lookup(x)?.let { return it }
        val end = x.trimEnd { it.isWhitespace() || it in ".:…!?" }
        if (end.isEmpty()) return null
        val tail = x.substring(end.length)
        if (tail.isNotEmpty()) lookup(end)?.let { return it + tail }
        val from = end.indexOfFirst { starts(it) }
        if (from > 0) lookup(end.substring(from))?.let { return end.substring(0, from) + it + tail }
        return null
    }

    /** Ek tukda: poora mile to theek, warna vaakya-vaakya (sabse lamba mel pehle). */
    private fun sentences(p: String): String {
        whole(p)?.let { return it }
        val parts = mutableListOf<String>()
        val gaps = mutableListOf<String>()
        var at = 0
        sentenceEnd.findAll(p).forEach { m ->
            parts.add(p.substring(at, m.range.first))
            gaps.add(m.value)
            at = m.range.last + 1
        }
        parts.add(p.substring(at))
        if (parts.size < 2 || parts.size > MAX_SENTENCES) return p
        val out = StringBuilder()
        var i = 0
        while (i < parts.size) {
            var done = false
            var j = parts.size
            while (j > i && !done) {
                val joined = StringBuilder()
                for (k in i until j) {
                    joined.append(parts[k])
                    if (k < j - 1) joined.append(gaps[k])
                }
                val got = if (joined.isBlank()) null else whole(joined.toString())
                if (got != null) {
                    out.append(got)
                    if (j - 1 < gaps.size) out.append(gaps[j - 1])
                    i = j
                    done = true
                }
                j--
            }
            if (!done) {
                out.append(parts[i])
                if (i < gaps.size) out.append(gaps[i])
                i++
            }
        }
        return out.toString()
    }

    private fun line(l: String): String {
        if (l.isBlank()) return l
        whole(l)?.let { return it }
        val out = StringBuilder()
        var at = 0
        var any = false
        breaks.findAll(l).forEach { m ->
            out.append(sentences(l.substring(at, m.range.first))).append(m.value)
            at = m.range.last + 1
            any = true
        }
        return if (any) out.append(sentences(l.substring(at))).toString() else sentences(l)
    }

    /** Screen ki line Marathi mein. Jo line nahi pehchani, wo waisi hi rehti hai (khabar ki heading, company ka naam). */
    fun t(s: String): String {
        if (!on || s.isBlank() || s.none { it in 'A'..'Z' || it in 'a'..'z' }) return s
        synchronized(cache) { cache[s] }?.let { return it }
        load()
        val out = s.split("\n").joinToString("\n") { line(it) }
        synchronized(cache) { cache[s] = out }
        return out
    }

    /** AI ko batao ki jawab Marathi mein likhe. Sahayak mein kuch nahi badalta. */
    fun ai(rules: String): String = if (!on) rules else rules + "\n\n" + AI_NOTE

    private const val AI_NOTE = "LANGUAGE: wherever the rules above say Hinglish or Roman Hindi, write in simple Marathi in Devanagari script instead, " +
        "the way people in Maharashtra speak every day. Keep the English words that Marathi speakers normally say in English, written in English letters: " +
        "share, market, trade, profit, loss, company, order, export, import, price, result, report, sector, bank, CEO, dividend, bonus, split, percent, " +
        "India, tariff, deal, business, news, fund and similar. Company names, people's names and all numbers stay exactly as they are. " +
        "The examples in the rules are in Hinglish only to show the style: do not copy their language."

    // ---------- Screen par lagana ----------

    private val shownText = WeakHashMap<View, CharSequence>()
    private val shownHint = WeakHashMap<View, CharSequence>()
    private val watched = WeakHashMap<View, Boolean>()

    /** Is screen (ya dialog) ki har likhi cheez dikhne se pehle Marathi ho jaye. */
    fun watch(root: View?) {
        if (!on || root == null || watched.containsKey(root)) return
        watched[root] = true
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean = try {
                !walk(root)
            } catch (e: RuntimeException) {
                true
            }
        })
    }

    fun watch(dialog: Dialog?) = watch(dialog?.window?.decorView)

    private fun same(a: CharSequence?, b: CharSequence?) = a === b || (a != null && b != null && a.length == b.length && a.toString() == b.toString())

    /** true = kuch badla (is baar mat banao, agli baar naya dikhega). */
    private fun walk(v: View): Boolean {
        var changed = false
        if (v is TextView) {
            val hint = v.hint
            if (hint != null && !same(hint, shownHint[v])) {
                val out = t(hint.toString())
                if (out != hint.toString()) {
                    v.hint = out
                    changed = true
                }
                shownHint[v] = v.hint
            }
            if (v !is EditText) {
                val text = v.text
                if (text != null && text.isNotEmpty() && !same(text, shownText[v])) {
                    val before = text.toString()
                    val out = t(before)
                    if (out != before) {
                        v.text = out
                        changed = true
                    }
                    shownText[v] = v.text
                }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (walk(v.getChildAt(i))) changed = true
        return changed
    }
}
