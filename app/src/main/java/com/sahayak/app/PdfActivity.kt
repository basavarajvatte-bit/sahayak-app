package com.sahayak.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Sahayak ka PDF viewer. Phone mein kahin bhi PDF kholo to "Open with" mein Sahayak aata hai.
 * Sahayak ke andar "📄 Open a PDF" se bhi file chun sakte ho. Pages upar-neeche scroll, zoom, share.
 */
class PdfActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var renderer: PdfRenderer? = null
    private var fd: ParcelFileDescriptor? = null
    private var file: File? = null
    private var source: Uri? = null
    private var count = 0
    private var zoom = 1.0f
    private var sizes = emptyList<Pair<Int, Int>>()
    private val cache = object : LruCache<String, Bitmap>(maxOf(16, (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt())) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private lateinit var list: ListView
    private lateinit var scroller: HorizontalScrollView
    private lateinit var title: TextView
    private lateinit var pageText: TextView
    private lateinit var status: TextView
    private lateinit var zoomText: TextView
    private val adapter = Pages()

    // ----- Search -----
    private val indexer = Executors.newSingleThreadExecutor()
    @Volatile private var openId = 0
    private val pageTexts = mutableListOf<String>()
    private val pageChars = mutableListOf<List<PdfLogic.Box?>>()
    private var indexDone = false
    private var matches = emptyList<PdfLogic.Match>()
    private var current = 0
    private lateinit var searchRow: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var searchText: TextView
    private val searchSoon = Runnable { runSearch(false) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun small(words: String, action: () -> Unit) = Button(this).apply {
        text = words
        isAllCaps = false
        textSize = 15f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF4B5563.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        title = TextView(this).apply {
            textSize = 17f
            setTextColor(0xFF1D2433.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        bar.addView(title)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(small("➖") { setZoom(PdfLogic.zoomOut(zoom)) })
        zoomText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF667085.toInt())
            setPadding(dp(4), 0, dp(4), 0)
        }
        buttons.addView(zoomText)
        buttons.addView(small("➕") { setZoom(PdfLogic.zoomIn(zoom)) })
        pageText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF1D2433.toInt())
            gravity = Gravity.CENTER
        }
        buttons.addView(pageText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(small("🔍") { toggleSearch() })
        buttons.addView(small("📤") { share() })
        buttons.addView(small("📂") { pick() })
        bar.addView(buttons)
        searchRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        searchField = EditText(this).apply {
            hint = "PDF mein dhoondo"
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    runSearch(true)
                    hideKeyboard()
                    true
                } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    main.removeCallbacks(searchSoon)
                    main.postDelayed(searchSoon, 400)
                }
            })
        }
        searchRow.addView(searchField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        searchText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF667085.toInt())
            setPadding(dp(4), 0, dp(4), 0)
        }
        searchRow.addView(searchText)
        searchRow.addView(small("◀") { step(-1) })
        searchRow.addView(small("▶") { step(1) })
        searchRow.addView(small("✕") { toggleSearch() })
        bar.addView(searchRow)
        page.addView(bar)
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        page.addView(status)
        scroller = HorizontalScrollView(this).apply { isFillViewport = true }
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            adapter = this@PdfActivity.adapter
            setOnTouchListener { _, e ->
                taps.onTouchEvent(e)
                false
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) = Unit
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    pageText.text = PdfLogic.pageLabel(first + 1, count)
                }
            })
        }
        scroller.addView(list, ViewGroup.LayoutParams(resources.displayMetrics.widthPixels, ViewGroup.LayoutParams.MATCH_PARENT))
        page.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
        zoomText.text = "100%"
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null) {
            title.text = "PDF"
            if (renderer == null) {
                status.text = "Koi PDF nahi khuli. 📂 dabake phone se PDF chuno."
                pick()
            }
            return
        }
        open(uri)
    }

    private fun pick() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"), PICK)
        } catch (e: RuntimeException) {
            Toast.makeText(this, "Is phone mein file chunne ka option nahi mila", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK && resultCode == RESULT_OK && uri != null) open(uri)
    }

    private fun nameOf(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: RuntimeException) {
        null
    } ?: uri.lastPathSegment

    /** File ko pehle Sahayak ki apni jagah copy karo (kuch apps sirf ek baar padhne dete hain), phir kholo. */
    private fun open(uri: Uri) {
        status.visibility = View.VISIBLE
        status.text = "PDF khul rahi hai…"
        val name = nameOf(uri)
        title.text = PdfLogic.title(name)
        worker.execute {
            val copy = File(cacheDir, "pdf/open.pdf")
            val answer: String? = try {
                copy.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                    ?: throw IOException("no stream")
                val pfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                val list = (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } }
                main.post {
                    closeRenderer()
                    fd = pfd
                    renderer = r
                    file = copy
                    source = uri
                    count = r.pageCount
                    sizes = list
                    cache.evictAll()
                    status.visibility = View.GONE
                    pageText.text = PdfLogic.pageLabel(1, count)
                    adapter.notifyDataSetChanged()
                    this.list.setSelection(0)
                    startIndex(copy)
                }
                null
            } catch (e: SecurityException) {
                "Ye PDF password se band hai. Sahayak abhi password wali PDF nahi khol sakta."
            } catch (e: IOException) {
                "Ye PDF khul nahi payi. File kharab ho sakti hai, ya poori download nahi hui."
            } catch (e: RuntimeException) {
                "Ye PDF khul nahi payi."
            }
            if (answer != null) main.post {
                status.visibility = View.VISIBLE
                status.text = answer
            }
        }
    }

    /** Zoom badlo. fx, fy = jis jagah ungli ne double tap kiya (list ke andar); wahi jagah screen par wahin rahe. */
    private fun setZoom(z: Float, fx: Float = -1f, fy: Float = -1f) {
        if (z == zoom || renderer == null) return
        val f = z / zoom
        val y = if (fy >= 0) fy.toInt() else 0
        var pos = if (fy >= 0) list.pointToPosition(maxOf(0, fx.toInt()), y) else AbsListView.INVALID_POSITION
        if (pos == AbsListView.INVALID_POSITION) pos = list.firstVisiblePosition
        val child = list.getChildAt(pos - list.firstVisiblePosition)
        val inside = if (child != null) y - child.top else 0
        val screenX = if (fx >= 0) fx - scroller.scrollX else 0f
        zoom = z
        zoomText.text = "${(z * 100).toInt()}%"
        cache.evictAll()
        list.layoutParams = list.layoutParams.apply { width = (resources.displayMetrics.widthPixels * z).toInt() }
        adapter.notifyDataSetChanged()
        list.setSelectionFromTop(pos, (y - inside * f).toInt())
        val newX = if (fx >= 0) (fx * f - screenX).toInt() else (scroller.scrollX * f).toInt()
        scroller.post { scroller.scrollTo(newX.coerceAtLeast(0), 0) }
    }

    /** Double tap: chhota ho to 200% (tap wali jagah par), bada ho to wapas 100%. */
    private val taps by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                setZoom(PdfLogic.doubleTapZoom(zoom), e.x, e.y)
                return true
            }
        })
    }

    private fun share() {
        val uri = source
        if (uri == null || renderer == null) {
            Toast.makeText(this, "Pehle koi PDF kholo", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Wahi asli file aage bhejo (jis app se aayi thi, usne padhne ki ijazat di hai)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "PDF bhejo"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share nahi ho paya", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeRenderer() {
        try {
            renderer?.close()
            fd?.close()
        } catch (e: Exception) {
            // Pehle hi band
        }
        renderer = null
        fd = null
    }

    override fun onDestroy() {
        openId++
        indexer.shutdownNow()
        worker.shutdownNow()
        synchronized(this) { closeRenderer() }
        super.onDestroy()
    }

    /** Ek page ki tasveer banao (peeche ke dhaage par). PdfRenderer ek baar mein ek hi page kholne deta hai. */
    private fun render(index: Int, z: Float): Bitmap? = synchronized(this) {
        val r = renderer ?: return null
        val (pw, ph) = sizes.getOrNull(index) ?: return null
        val (w, h) = PdfLogic.bitmapSize(resources.displayMetrics.widthPixels - dp(8), z, pw, ph)
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            r.openPage(index).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            bmp
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private inner class Pages : BaseAdapter() {
        override fun getCount(): Int = this@PdfActivity.count
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? PageView) ?: PageView().apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val z = zoom
            val key = "$position@$z"
            view.tag = key
            val (pw, ph) = sizes.getOrNull(position) ?: (1 to 1)
            val (w, h) = PdfLogic.bitmapSize(resources.displayMetrics.widthPixels - dp(8), z, pw, ph)
            view.minimumHeight = h
            val have = cache.get(key)
            view.layoutParams = AbsListView.LayoutParams(w + dp(8), h + dp(8))
            view.marks = marksFor(position)
            if (have != null) {
                view.setImageBitmap(have)
            } else {
                view.setImageBitmap(null)
                view.setBackgroundColor(Color.WHITE)
                worker.execute {
                    val bmp = render(position, z) ?: return@execute
                    main.post {
                        cache.put(key, bmp)
                        if (view.tag == key) view.setImageBitmap(bmp)
                    }
                }
            }
            return view
        }
    }

    // ---------- Search ----------

    /** Mile hue shabdon ke dabbe is page par: (sab, abhi wala). */
    private fun marksFor(page: Int): Pair<List<PdfLogic.Box>, List<PdfLogic.Box>> {
        if (matches.isEmpty() || page >= pageChars.size) return emptyList<PdfLogic.Box>() to emptyList()
        val chars = pageChars[page]
        val all = mutableListOf<PdfLogic.Box>()
        var now = emptyList<PdfLogic.Box>()
        matches.forEachIndexed { i, m ->
            if (m.page != page) return@forEachIndexed
            val b = PdfLogic.boxes(chars, m.start, m.end)
            if (i == current) now = b else all += b
        }
        return all to now
    }

    private fun toggleSearch() {
        if (searchRow.visibility == View.VISIBLE) {
            searchRow.visibility = View.GONE
            matches = emptyList()
            adapter.notifyDataSetChanged()
            hideKeyboard()
        } else {
            searchRow.visibility = View.VISIBLE
            searchField.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
            if (!indexDone) searchText.text = "Taiyaar ho raha hai…"
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    /** PDF ke andar ke shabd aur unki jagah padho (peeche, page-page karke). */
    private fun startIndex(copy: File) {
        val id = ++openId
        pageTexts.clear()
        pageChars.clear()
        indexDone = false
        matches = emptyList()
        indexer.execute {
            try {
                PDFBoxResourceLoader.init(applicationContext)
                PDDocument.load(copy).use { doc ->
                    val n = doc.numberOfPages
                    for (i in 0 until n) {
                        if (id != openId) return@use
                        val pg = doc.getPage(i)
                        val box = pg.cropBox
                        val turned = pg.rotation % 180 != 0
                        val w = if (turned) box.height else box.width
                        val h = if (turned) box.width else box.height
                        val sb = StringBuilder()
                        val chars = ArrayList<PdfLogic.Box?>()
                        val stripper = object : PDFTextStripper() {
                            override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                                positions?.forEach { tp ->
                                    val u = tp.unicode ?: return@forEach
                                    val bx = PdfLogic.Box(tp.xDirAdj / w, (tp.yDirAdj - tp.heightDir) / h, (tp.xDirAdj + tp.widthDirAdj) / w, tp.yDirAdj / h)
                                    for (ch in u) {
                                        sb.append(ch)
                                        chars.add(bx)
                                    }
                                }
                            }

                            override fun writeWordSeparator() = space()
                            override fun writeLineSeparator() = space()

                            private fun space() {
                                if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') {
                                    sb.append(' ')
                                    chars.add(null)
                                }
                            }
                        }
                        stripper.sortByPosition = true
                        stripper.startPage = i + 1
                        stripper.endPage = i + 1
                        try {
                            stripper.getText(doc)
                        } catch (e: Exception) {
                            // Ye page padha nahi gaya; khaali maano
                        }
                        main.post {
                            if (id != openId) return@post
                            pageTexts += sb.toString()
                            pageChars += chars
                            if (searchRow.visibility == View.VISIBLE && searchField.text.isNotBlank() && (i % 10 == 9 || i == n - 1)) runSearch(false)
                            else if (searchRow.visibility == View.VISIBLE && searchField.text.isBlank()) searchText.text = "Padh raha hoon ${i + 1}/$n"
                        }
                    }
                }
            } catch (e: Exception) {
                // Password ya kharab file: search nahi hoga
            } catch (e: OutOfMemoryError) {
                // Bahut badi PDF
            }
            main.post {
                if (id != openId) return@post
                indexDone = true
                if (searchRow.visibility == View.VISIBLE) runSearch(false)
            }
        }
    }

    private fun runSearch(jump: Boolean) {
        val q = searchField.text.toString()
        if (q.isBlank()) {
            matches = emptyList()
            searchText.text = ""
            adapter.notifyDataSetChanged()
            return
        }
        val before = matches.getOrNull(current)
        matches = PdfLogic.find(pageTexts, q)
        current = if (before != null) matches.indexOfFirst { it.page >= before.page }.coerceAtLeast(0) else 0
        searchText.text = when {
            matches.isNotEmpty() -> PdfLogic.resultLabel(current, matches.size) + if (!indexDone) " …" else ""
            !indexDone -> "Dhoondh raha hoon ${pageTexts.size}/$count"
            pageTexts.all { it.isBlank() } -> "Is PDF mein likha hua text nahi hai (scan ki hui photo hai)"
            else -> "Nahi mila"
        }
        adapter.notifyDataSetChanged()
        if (matches.isNotEmpty() && (jump || before == null)) show(current)
    }

    private fun step(d: Int) {
        if (matches.isEmpty()) {
            runSearch(true)
            return
        }
        current = (current + d + matches.size) % matches.size
        searchText.text = PdfLogic.resultLabel(current, matches.size)
        adapter.notifyDataSetChanged()
        show(current)
        hideKeyboard()
    }

    /** Mile hue shabd tak le jao: sahi page, aur page mein us jagah tak. */
    private fun show(i: Int) {
        val m = matches.getOrNull(i) ?: return
        val b = PdfLogic.boxes(pageChars[m.page], m.start, m.end).firstOrNull()
        val (pw, ph) = sizes.getOrNull(m.page) ?: (1 to 1)
        val (w, h) = PdfLogic.bitmapSize(resources.displayMetrics.widthPixels - dp(8), zoom, pw, ph)
        val top = if (b != null) (b.t * h).toInt() else 0
        list.setSelectionFromTop(m.page, -top + list.height / 3)
        if (b != null) scroller.smoothScrollTo(((b.l * w).toInt() - resources.displayMetrics.widthPixels / 3).coerceAtLeast(0), 0)
        else scroller.smoothScrollTo(0, 0)
    }

    /** Page ki tasveer, upar peele (aur abhi wale par narangi) nishaan. */
    private inner class PageView : ImageView(this@PdfActivity) {
        var marks: Pair<List<PdfLogic.Box>, List<PdfLogic.Box>> = emptyList<PdfLogic.Box>() to emptyList()
            set(v) {
                field = v
                invalidate()
            }
        private val yellow = Paint().apply { color = 0x66FFD600 }
        private val orange = Paint().apply { color = 0x88FF7A00.toInt() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (marks.first.isEmpty() && marks.second.isEmpty()) return
            val l = paddingLeft.toFloat()
            val t = paddingTop.toFloat()
            val w = (width - paddingLeft - paddingRight).toFloat()
            val h = (height - paddingTop - paddingBottom).toFloat()
            val pad = 2f
            for ((list, paint) in listOf(marks.first to yellow, marks.second to orange)) {
                for (b in list) canvas.drawRect(l + b.l * w - pad, t + b.t * h - pad, l + b.r * w + pad, t + b.b * h + pad, paint)
            }
        }
    }

    companion object {
        private const val PICK = 91
    }
}
