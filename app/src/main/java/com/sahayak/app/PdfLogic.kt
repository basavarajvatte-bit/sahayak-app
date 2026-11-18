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
}
