package com.sahayak.app

/** PDF viewer ka chhota hisaab (screen se alag, taaki test ho sake). */
object PdfLogic {
    val ZOOMS = listOf(1.0f, 1.5f, 2.0f, 3.0f)

    fun zoomIn(z: Float): Float = ZOOMS.firstOrNull { it > z + 0.01f } ?: ZOOMS.last()

    fun zoomOut(z: Float): Float = ZOOMS.lastOrNull { it < z - 0.01f } ?: ZOOMS.first()

    /** "Page 3 / 12" */
    fun pageLabel(page: Int, count: Int): String = if (count <= 0) "" else "Page ${page.coerceIn(1, count)} / $count"

    /** Page ki bitmap kitni chaudi aur oonchi bane: screen ki chaudai × zoom, PDF page ka aakar same rahe. Bahut badi na ho. */
    fun bitmapSize(screenWidth: Int, zoom: Float, pageW: Int, pageH: Int, maxPixels: Int = 12_000_000): Pair<Int, Int> {
        if (pageW <= 0 || pageH <= 0 || screenWidth <= 0) return 1 to 1
        var w = (screenWidth * zoom).toInt()
        var h = (w.toLong() * pageH / pageW).toInt()
        if (w.toLong() * h > maxPixels) {
            val scale = Math.sqrt(maxPixels.toDouble() / (w.toLong() * h))
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    /** "Bill march.pdf" -> "Bill march" */
    fun title(name: String?): String {
        val n = name?.substringAfterLast('/')?.trim().orEmpty()
        if (n.isEmpty()) return "PDF"
        return if (n.endsWith(".pdf", ignoreCase = true)) n.dropLast(4) else n
    }

    /** Page par ek akshar ki jagah, 0 se 1 ke hisaab mein (baayein, upar, daayein, neeche). */
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float)

    /** Ek mila hua shabd: kaunsa page, page ke text mein kahan se kahan tak. */
    data class Match(val page: Int, val start: Int, val end: Int)

    /** Saare pages mein dhoondo (chhote-bade akshar ka fark nahi, beech ke kai space ek gine). */
    fun find(pages: List<String>, query: String, limit: Int = 2000): List<Match> {
        val q = query.trim().replace(Regex("\\s+"), " ").lowercase()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Match>()
        pages.forEachIndexed { p, text ->
            val low = text.lowercase()
            var i = low.indexOf(q)
            while (i >= 0 && out.size < limit) {
                out += Match(p, i, i + q.length)
                i = low.indexOf(q, i + 1)
            }
        }
        return out
    }

    /** Mile hue aksharon ke dabbe, har line ka ek bada dabba. */
    fun boxes(chars: List<Box?>, start: Int, end: Int): List<Box> {
        val out = mutableListOf<Box>()
        for (i in start until minOf(end, chars.size)) {
            val b = chars[i] ?: continue
            val last = out.lastOrNull()
            val sameLine = last != null && b.t < last.b && b.b > last.t
            if (sameLine) out[out.size - 1] = Box(minOf(last!!.l, b.l), minOf(last.t, b.t), maxOf(last.r, b.r), maxOf(last.b, b.b))
            else out += b
        }
        return out
    }

    /** "3 / 17" ya "Nahi mila" */
    fun resultLabel(current: Int, total: Int): String = if (total == 0) "Nahi mila" else "${current + 1} / $total"
}
