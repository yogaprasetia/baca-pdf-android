package id.yoga.bacapdf

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

class ReaderActivity : AppCompatActivity() {
  companion object {
    const val HOST = "appassets.androidplatform.net"
    val COLORS = linkedMapOf("Kuning" to "#FFFF98", "Hijau" to "#53FFBC", "Biru" to "#80EBFF", "Pink" to "#FFCBE6", "Merah" to "#FF4F5F")
    val INK_COLORS = linkedMapOf("Merah" to "#E5484D", "Biru" to "#0A6CFF", "Hitam" to "#111111", "Hijau" to "#1F9D55", "Kuning" to "#FFC94D")
    const val M_NONE = 0; const val M_TEXT = 3; const val M_HL = 9; const val M_INK = 15
  }

  private lateinit var doc: Doc
  private lateinit var web: PdfWebView
  private lateinit var topBar: LinearLayout
  private lateinit var titleTv: TextView
  private lateinit var subTv: TextView
  private lateinit var bmBtn: MaterialButton
  private lateinit var searchRow: LinearLayout
  private lateinit var searchEt: EditText
  private lateinit var findCount: TextView
  private lateinit var mainRow: LinearLayout
  private lateinit var bottomBar: LinearLayout
  private lateinit var pageTv: TextView
  private lateinit var seek: SeekBar
  private lateinit var toolRow: LinearLayout
  private lateinit var colorRow: LinearLayout
  private lateinit var selBar: View
  private lateinit var loading: ProgressBar
  private lateinit var slideBtn: MaterialButton
  private val toolBtns = HashMap<Int, MaterialButton>()

  private val ui = Handler(Looper.getMainLooper())
  private val io = Executors.newSingleThreadExecutor()
  private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
  private val pending = HashMap<String, (String) -> Unit>()
  private var reqSeq = 0

  private var ready = false
  private var pages = 0
  private var page = 1
  private var mode = M_NONE
  private var modified = false
  private var barsShown = true
  private var searching = false
  private var finishing = false
  private var saveJob: SaveJob? = null
  private var afterSave: ((Boolean) -> Unit)? = null
  private var lastSaveAt = 0L
  private var hlColor: String = "#FFFF98"
  private var inkColor: String = "#E5484D"

  private class SaveJob(val id: String, val tmp: File, val out: FileOutputStream)

  // ======================================================================
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Library.load(this)
    val d = Library.get(intent.getStringExtra("id") ?: "")
    if (d == null) { finish(); return }
    doc = d
    doc.lastOpened = System.currentTimeMillis(); Library.save(this)
    hlColor = prefs.getString("hlColor", hlColor)!!
    inkColor = prefs.getString("inkColor", inkColor)!!
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (prefs.getBoolean("keepOn", true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    buildUi()
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() = onBack()
    })
  }

  // ======================================================================
  // Tampilan
  // ======================================================================
  @SuppressLint("SetJavaScriptEnabled")
  private fun buildUi() {
    val root = FrameLayout(this)
    web = PdfWebView(this)
    root.addView(web, FrameLayout.LayoutParams(-1, -1))
    web.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = false
      allowContentAccess = false
      setSupportZoom(false)
      builtInZoomControls = false
      displayZoomControls = false
      textZoom = 100
      useWideViewPort = true
      loadWithOverviewMode = false
    }
    web.isVerticalScrollBarEnabled = false
    web.setBackgroundColor(Color.parseColor("#2B2F36"))
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
    web.addJavascriptInterface(Bridge(), "Android")
    web.webViewClient = object : WebViewClient() {
      override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? = intercept(req.url)
      override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
        val u = req.url
        if (u.host == HOST) return false
        // tautan luar di PDF → buka di browser
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
        return true
      }
    }
    web.onHighlight = { color, note -> highlightSelection(color, note) }

    loading = ProgressBar(this).apply { isIndeterminate = true }
    root.addView(loading, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

    // ---------- bar atas ----------
    topBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F21B1F24")) }
    mainRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
    mainRow.addView(iconBtn(R.drawable.ic_back, "Kembali") { onBack() })
    val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
    titleTv = TextView(this).apply { textSize = 16f; setTextColor(col(R.color.text)); setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; text = doc.name.removeSuffix(".pdf") }
    subTv = TextView(this).apply { textSize = 12f; setTextColor(col(R.color.muted)); maxLines = 1; text = "Membuka…" }
    tcol.addView(titleTv); tcol.addView(subTv)
    tcol.setOnClickListener { gotoDialog() }
    mainRow.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
    mainRow.addView(iconBtn(R.drawable.ic_search, "Cari") { openSearch() })
    bmBtn = iconBtn(R.drawable.ic_bookmark_border, "Penanda") { toggleBookmark() }
    mainRow.addView(bmBtn)
    mainRow.addView(iconBtn(R.drawable.ic_toc, "Daftar isi, highlight, penanda") { openSheet(-1) })
    mainRow.addView(iconBtn(R.drawable.ic_more, "Menu") { moreMenu() })
    topBar.addView(mainRow)

    searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)); visibility = View.GONE }
    searchRow.addView(iconBtn(R.drawable.ic_close, "Tutup pencarian") { closeSearch() })
    searchEt = EditText(this).apply {
      hint = "Cari di dokumen…"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
      setTextColor(col(R.color.text)); setHintTextColor(col(R.color.muted)); background = round(col(R.color.surface2), dpf(10f))
      setPadding(dp(12), dp(8), dp(12), dp(8))
      setOnEditorActionListener { _, _, _ -> find(again = true, prev = false); true }
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(findRunnable); ui.postDelayed(findRunnable, 350) }
      })
    }
    searchRow.addView(searchEt, LinearLayout.LayoutParams(0, -2, 1f))
    findCount = TextView(this).apply { setTextColor(col(R.color.muted)); textSize = 13f; setPadding(dp(8), 0, dp(4), 0); minWidth = dp(44); gravity = Gravity.CENTER }
    searchRow.addView(findCount)
    searchRow.addView(iconBtn(R.drawable.ic_up, "Sebelumnya") { find(again = true, prev = true) })
    searchRow.addView(iconBtn(R.drawable.ic_down, "Berikutnya") { find(again = true, prev = false) })
    topBar.addView(searchRow)
    root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

    // ---------- bar seleksi teks (muncul saat teks dipilih) ----------
    selBar = buildSelBar()
    selBar.visibility = View.GONE

    // ---------- bar bawah ----------
    bottomBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F21B1F24")); setPadding(dp(8), dp(4), dp(8), dp(4)) }
    bottomBar.addView(selBar, LinearLayout.LayoutParams(-1, -2))
    colorRow = LinearLayout(this).apply { gravity = Gravity.CENTER; visibility = View.GONE; setPadding(0, dp(4), 0, dp(2)) }
    bottomBar.addView(colorRow, LinearLayout.LayoutParams(-1, -2))
    val pageRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    pageTv = TextView(this).apply { setTextColor(col(R.color.text)); textSize = 13f; minWidth = dp(70); gravity = Gravity.CENTER; setOnClickListener { gotoDialog() } }
    seek = SeekBar(this).apply {
      progressTintList = ColorStateList.valueOf(col(R.color.accent)); thumbTintList = ColorStateList.valueOf(col(R.color.accent))
      setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) pageTv.text = "${p + 1} / $pages" }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) { js("APP.goto(${s.progress + 1})") }
      })
    }
    slideBtn = iconBtn(R.drawable.ic_slide, "Mode slide (geser per halaman)") { toggleSlide() }
    pageRow.addView(slideBtn)
    pageRow.addView(pageTv); pageRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
    bottomBar.addView(pageRow)
    toolRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    fun tool(m: Int, icon: Int, label: String) = toolBtn(icon, label) { setMode(if (mode == m && m != M_NONE) M_NONE else m) }.also { toolBtns[m] = it; toolRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    tool(M_NONE, R.drawable.ic_hand, "Baca")
    tool(M_HL, R.drawable.ic_highlight, "Stabilo")
    tool(M_INK, R.drawable.ic_pen, "Pena")
    tool(M_TEXT, R.drawable.ic_text, "Teks")
    toolRow.addView(toolBtn(R.drawable.ic_undo, "Urungkan") { js("APP.undo()") }, LinearLayout.LayoutParams(0, -2, 1f))
    toolRow.addView(toolBtn(R.drawable.ic_redo, "Ulangi") { js("APP.redo()") }, LinearLayout.LayoutParams(0, -2, 1f))
    bottomBar.addView(toolRow)
    root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    updateToolUi()

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
      val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
      topBar.setPadding(b.left, b.top, b.right, 0)
      bottomBar.setPadding(dp(8) + b.left, dp(4), dp(8) + b.right, dp(4) + maxOf(b.bottom, ime.bottom))
      topBar.post { pushPadding() }
      ins
    }
    setContentView(root)
    web.loadUrl("https://$HOST/pdfjs/web/viewer.html")
  }

  private fun buildSelBar(): View {
    val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(6)) }
    row.addView(TextView(this).apply { text = "Highlight:"; setTextColor(col(R.color.muted)); textSize = 13f; setPadding(dp(6), 0, dp(6), 0) })
    for ((name, hex) in COLORS) row.addView(colorDot(hex, name, false) { highlightSelection(hex, false) })
    row.addView(chip(R.drawable.ic_note, "Catatan") { highlightSelection(hlColor, true) })
    row.addView(chip(R.drawable.ic_copy, "Salin") { copySelection() })
    row.addView(chip(R.drawable.ic_share, "Bagikan") { shareSelection() })
    sc.addView(row)
    return sc
  }

  private fun pushPadding() {
    val d = resources.displayMetrics.density
    val top = (topBar.height / d).toInt()
    js("document.documentElement.style.setProperty('--top-pad','${top}px');document.documentElement.style.setProperty('--bottom-pad','${(bottomBar.height / d).toInt()}px')")
  }

  // ======================================================================
  // Sumber file untuk WebView (aset PDF.js + dokumen)
  // ======================================================================
  private fun intercept(u: Uri): WebResourceResponse? {
    if (u.host != HOST) return null
    val path = u.path ?: return notFound()
    return try {
      when {
        path.startsWith("/pdfjs/") -> {
          val m = mime(path)
          val text = m.startsWith("text/") || m == "application/json"
          WebResourceResponse(m, if (text) "utf-8" else null, assets.open(path.substring(1)))
        }
        path.startsWith("/doc/") -> {
          val (stream, len) = openDoc()
          val h = hashMapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")
          if (len > 0) h["Content-Length"] = len.toString()
          WebResourceResponse("application/pdf", null, 200, "OK", h, stream)
        }
        else -> notFound()
      }
    } catch (e: Exception) { notFound() }
  }
  private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
  private fun mime(p: String) = when (p.substringAfterLast('.').lowercase()) {
    "html" -> "text/html"; "mjs", "js" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
    "ftl" -> "text/plain"; "wasm" -> "application/wasm"; "svg" -> "image/svg+xml"; "png" -> "image/png"; "gif" -> "image/gif"
    "ttf" -> "font/ttf"; "otf" -> "font/otf"; else -> "application/octet-stream"
  }
  private fun openDoc(): Pair<InputStream, Long> {
    val u = Uri.parse(doc.uri)
    return if (u.scheme == ContentResolver.SCHEME_FILE) { val f = File(u.path!!); FileInputStream(f) to f.length() }
    else {
      val len = try { contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
      contentResolver.openInputStream(u)!! to len
    }
  }

  // ======================================================================
  // Jembatan JavaScript → Kotlin
  // ======================================================================
  inner class Bridge {
    @JavascriptInterface fun onReady() = ui.post {
      ready = true
      applyTheme(); pushPadding()
      js("APP.open(${q("https://$HOST/doc/${doc.id}?v=${System.currentTimeMillis()}")}, ${q(doc.hash)}, ${q(doc.id)})")
    }.let { }
    @JavascriptInterface fun onDoc(json: String) = ui.post {
      val o = JSONObject(json)
      pages = o.optInt("pages"); doc.pages = pages
      o.optString("fingerprint").takeIf { it.isNotEmpty() }?.let { doc.fingerprint = it }
      loading.visibility = View.GONE
      seek.max = (pages - 1).coerceAtLeast(0)
      applyScroll()
      onPageChanged(o.optInt("page", 1))
      Library.save(this@ReaderActivity)
      if (!prefs.getBoolean("tipShown", false)) {
        prefs.edit().putBoolean("tipShown", true).apply()
        toast("Tahan teks untuk highlight. Ketuk tengah layar untuk menyembunyikan bar.")
      }
    }.let { }
    @JavascriptInterface fun onPage(p: Int, total: Int) = ui.post { if (total > 0) pages = total; onPageChanged(p) }.let { }
    @JavascriptInterface fun onViewArea(p: Int, hash: String, scale: String) = ui.post {
      doc.page = p; doc.hash = hash
      throttledLibrarySave()
    }.let { }
    @JavascriptInterface fun onModified(m: Boolean) = ui.post {
      modified = m
      if (m) scheduleAutoSave()
      updateSub()
    }.let { }
    @JavascriptInterface fun onSaveChunk(id: String, b64: String, first: Boolean) {
      val j = saveJob ?: return
      if (j.id != id) return
      try { j.out.write(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) {}
    }
    @JavascriptInterface fun onSaveDone(id: String, len: Int) {
      val j = saveJob ?: return
      if (j.id != id) return
      try { j.out.close() } catch (_: Exception) {}
      io.execute { writeBack(j, len.toLong()) }
    }
    @JavascriptInterface fun onSaveError(id: String, msg: String) = ui.post {
      saveJob?.let { try { it.out.close() } catch (_: Exception) {}; it.tmp.delete() }
      saveJob = null
      toast("Gagal menyimpan: $msg")
      updateSub()
      afterSave?.invoke(false); afterSave = null
    }.let { }
    @JavascriptInterface fun onFind(cur: Int, total: Int, state: Int) = ui.post {
      findCount.text = when {
        state == 1 -> "0"
        total > 0 -> "$cur/$total"
        state == 3 -> "…"
        else -> findCount.text
      }
    }.let { }
    @JavascriptInterface fun onTap(zone: String) = ui.post { onTapZone(zone) }.let { }
    @JavascriptInterface fun onError(msg: String) = ui.post {
      loading.visibility = View.GONE
      if (pages > 0) { toast(msg); return@post }
      MaterialAlertDialogBuilder(this@ReaderActivity).setTitle("PDF tidak bisa dibuka").setMessage(msg)
        .setPositiveButton("Tutup") { _, _ -> finish() }.setCancelable(false).show()
    }.let { }
    @JavascriptInterface fun onResult(id: String, json: String) = ui.post { pending.remove(id)?.invoke(json) }.let { }
    @JavascriptInterface fun onMode(m: Int) = ui.post { mode = m; updateToolUi() }.let { }
    @JavascriptInterface fun onSelection(has: Boolean) = ui.post {
      selBar.visibility = if (has && mode == M_NONE) View.VISIBLE else View.GONE
      if (has && !barsShown) showBars(true)
    }.let { }
    @JavascriptInterface fun onPassword() = ui.post { loading.visibility = View.GONE; showBars(true) }.let { }
  }

  private fun js(code: String, cb: ((String) -> Unit)? = null) {
    if (!::web.isInitialized) return
    web.evaluateJavascript(code) { cb?.invoke(it) }
  }
  /** panggil fungsi APP.x(id, ...) yang menjawab lewat onResult */
  private fun call(fn: String, vararg args: String, cb: (String) -> Unit) {
    val id = "r${++reqSeq}"
    pending[id] = cb
    js("APP.$fn(${(listOf(q(id)) + args).joinToString(",")})")
  }
  private fun q(s: String) = JSONObject.quote(s)

  // ======================================================================
  // Halaman & bar
  // ======================================================================
  private fun onPageChanged(p: Int) {
    page = p
    doc.page = p
    pageTv.text = "$p / $pages"
    seek.progress = p - 1
    updateSub()
    val marked = doc.bookmarks.any { it.page == p }
    bmBtn.setIconResource(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
    bmBtn.iconTint = ColorStateList.valueOf(col(if (marked) R.color.accent else R.color.text))
  }
  private fun updateSub() {
    val s = StringBuilder()
    if (pages > 0) s.append("Hal $page dari $pages · ${(page * 100 / pages)}%")
    when {
      saveJob != null -> s.append("  ·  Menyimpan…")
      modified -> s.append("  ·  ● belum disimpan")
    }
    subTv.text = s
  }

  private var libSaveQueued = false
  private fun throttledLibrarySave() {
    if (libSaveQueued) return
    libSaveQueued = true
    ui.postDelayed({ libSaveQueued = false; Library.save(this) }, 2500)
  }

  private fun onTapZone(zone: String) {
    val scroll = prefs.getInt("scroll", 0)
    val paged = scroll == 3
    if (zone != "center" && paged && prefs.getBoolean("edgeTap", true)) {
      js(if (zone == "left") "APP.prev()" else "APP.next()")
      return
    }
    showBars(!barsShown)
  }

  private fun showBars(show: Boolean) {
    if (show == barsShown) return
    barsShown = show
    val c = WindowInsetsControllerCompat(window, window.decorView)
    if (show) {
      c.show(WindowInsetsCompat.Type.systemBars())
      topBar.animate().translationY(0f).alpha(1f).setDuration(160).start()
      bottomBar.animate().translationY(0f).alpha(1f).setDuration(160).start()
    } else {
      if (searching || mode != M_NONE) { barsShown = true; return }
      c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      c.hide(WindowInsetsCompat.Type.systemBars())
      topBar.animate().translationY(-topBar.height.toFloat()).alpha(0f).setDuration(160).start()
      bottomBar.animate().translationY(bottomBar.height.toFloat()).alpha(0f).setDuration(160).start()
    }
  }

  // ======================================================================
  // Alat: highlight, pena, teks
  // ======================================================================
  private fun setMode(m: Int) {
    js("APP.mode($m)")
    mode = m
    if (m == M_HL) js("APP.color('highlight', ${q(hlColor)})")
    if (m == M_INK) js("APP.color('ink', ${q(inkColor)})")
    if (m == M_TEXT) js("APP.color('text', ${q(inkColor)})")
    updateToolUi()
    if (m != M_NONE) {
      showBars(true)
      toast(when (m) { M_HL -> "Mode stabilo: sapukan jari di atas teks"; M_INK -> "Mode pena: menggambar dengan jari"; else -> "Mode teks: ketuk halaman untuk menulis" } + ". Ketuk Baca untuk kembali menggulir.", short = true)
    } else if (modified) scheduleAutoSave()
  }

  private fun updateToolUi() {
    for ((m, b) in toolBtns) {
      val on = m == mode
      b.iconTint = ColorStateList.valueOf(col(if (on) R.color.accent else R.color.text))
      b.setTextColor(col(if (on) R.color.accent else R.color.muted))
      b.backgroundTintList = ColorStateList.valueOf(if (on) col(R.color.accentDark) else Color.TRANSPARENT)
    }
    colorRow.removeAllViews()
    if (mode == M_HL || mode == M_INK || mode == M_TEXT) {
      val set = if (mode == M_HL) COLORS else INK_COLORS
      val cur = if (mode == M_HL) hlColor else inkColor
      colorRow.addView(TextView(this).apply { text = if (mode == M_HL) "Warna stabilo:" else "Warna:"; setTextColor(col(R.color.muted)); textSize = 13f; setPadding(0, 0, dp(6), 0) })
      for ((name, hex) in set) colorRow.addView(colorDot(hex, name, hex.equals(cur, true)) {
        if (mode == M_HL) { hlColor = hex; prefs.edit().putString("hlColor", hex).apply(); js("APP.color('highlight', ${q(hex)})") }
        else { inkColor = hex; prefs.edit().putString("inkColor", hex).apply(); js("APP.color(${q(if (mode == M_INK) "ink" else "text")}, ${q(hex)})") }
        updateToolUi()
      })
      colorRow.addView(chip(R.drawable.ic_delete, "Hapus terpilih") { js("APP.deleteSelected()") })
      colorRow.visibility = View.VISIBLE
      selBar.visibility = View.GONE
    } else colorRow.visibility = View.GONE
    bottomBar.post { pushPadding() }
  }

  private fun highlightSelection(color: String, note: Boolean) {
    hlColor = color; prefs.edit().putString("hlColor", color).apply()
    js("APP.highlight(${q(color)}, $note)") { r ->
      web.finishActionMode()
      if (r != "true") toast("Pilih teks dulu (tahan pada tulisan)", short = true)
      else if (note) toast("Tulis catatan lalu tekan Simpan", short = true)
    }
  }

  private fun copySelection() {
    js("APP.selectionText()") { r ->
      val t = try { JSONArray("[$r]").getString(0) } catch (_: Exception) { "" }
      if (t.isBlank()) return@js
      (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF", t))
      toast("Teks disalin", short = true)
      js("APP.clearSelection()"); web.finishActionMode()
    }
  }
  private fun shareSelection() {
    js("APP.selectionText()") { r ->
      val t = try { JSONArray("[$r]").getString(0) } catch (_: Exception) { "" }
      if (t.isBlank()) return@js
      startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "“${t.trim()}”\n— ${doc.name.removeSuffix(".pdf")}, hal. $page"), "Bagikan teks"))
    }
  }

  // ======================================================================
  // Simpan highlight ke file PDF
  // ======================================================================
  private val autoSave = Runnable { if (modified && mode == M_NONE && saveJob == null && !finishing) save(commit = false) }
  private fun scheduleAutoSave() {
    if (!prefs.getBoolean("autosave", true)) return
    ui.removeCallbacks(autoSave)
    val big = doc.size > 40L * 1024 * 1024
    ui.postDelayed(autoSave, if (big) 60_000 else 4_000)
  }

  private fun save(commit: Boolean = true, then: ((Boolean) -> Unit)? = null) {
    if (saveJob != null) { if (then != null) afterSave = then; return }
    if (!ready || pages == 0) { then?.invoke(true); return }
    val id = "s${System.currentTimeMillis()}"
    val tmp = File(cacheDir, "save-$id.pdf")
    saveJob = SaveJob(id, tmp, FileOutputStream(tmp))
    afterSave = then
    updateSub()
    js("APP.save(${q(id)}, $commit)")
  }

  /** dipanggil di thread IO setelah semua potongan diterima */
  private fun writeBack(j: SaveJob, len: Long) {
    var ok = false
    var msg: String? = null
    try {
      val head = ByteArray(5).also { b -> FileInputStream(j.tmp).use { it.read(b) } }
      if (j.tmp.length() != len || String(head) != "%PDF-") throw IllegalStateException("data simpan tidak utuh")
      val u = Uri.parse(doc.uri)
      if (u.scheme == ContentResolver.SCHEME_FILE) {
        val dst = File(u.path!!)
        val t2 = File(dst.path + ".tmp")
        j.tmp.copyTo(t2, overwrite = true)
        if (!t2.renameTo(dst)) { dst.delete(); t2.renameTo(dst) }
        ok = true
      } else {
        var written = false
        for (m in listOf("wt", "rwt", "w")) {
          try {
            contentResolver.openOutputStream(u, m)?.use { out -> FileInputStream(j.tmp).use { it.copyTo(out, 1 shl 16) } } ?: continue
            written = true; break
          } catch (_: Exception) {}
        }
        if (written) ok = true
        else {
          // file asli tidak bisa ditulisi (mis. dari Google Drive/WhatsApp) → simpan sebagai salinan di aplikasi
          val dir = File(filesDir, "docs").apply { mkdirs() }
          var f = File(dir, doc.name.let { if (it.lowercase().endsWith(".pdf")) it else "$it.pdf" })
          var i = 2
          while (f.exists()) { f = File(dir, doc.name.removeSuffix(".pdf") + " ($i).pdf"); i++ }
          j.tmp.copyTo(f)
          doc.uri = Uri.fromFile(f).toString(); doc.copy = true
          msg = "File asli tidak bisa diubah, jadi PDF ber-highlight disimpan sebagai salinan di aplikasi. Bagikan lewat menu ⋮ → Bagikan PDF."
          ok = true
        }
      }
      doc.size = len
    } catch (e: Exception) { msg = "Gagal menyimpan: ${e.message}" }
    finally { j.tmp.delete() }
    ui.post {
      saveJob = null
      lastSaveAt = System.currentTimeMillis()
      Pdf.dropThumb(this, doc.id)
      Library.save(this)
      updateSub()
      if (msg != null) MaterialAlertDialogBuilder(this).setMessage(msg).setPositiveButton("OK", null).show()
      afterSave?.invoke(ok); afterSave = null
    }
  }

  override fun onPause() {
    super.onPause()
    Library.save(this)
    if (modified && !finishing) save(commit = false)
  }

  private fun onBack() {
    when {
      searching -> closeSearch()
      mode != M_NONE -> setMode(M_NONE)
      else -> saveAndFinish()
    }
  }
  private fun saveAndFinish() {
    if (finishing) return
    finishing = true
    if (!modified && saveJob == null) { finish(); return }
    subTv.text = "Menyimpan highlight…"
    val dlg = MaterialAlertDialogBuilder(this).setView(ProgressBar(this).apply { setPadding(0, dp(24), 0, dp(24)) }).setMessage("Menyimpan highlight ke PDF…").setCancelable(false).show()
    val done: (Boolean) -> Unit = { ok ->
      dlg.dismiss()
      if (ok) finish()
      else {
        finishing = false
        MaterialAlertDialogBuilder(this).setTitle("Highlight belum tersimpan").setMessage("Keluar tanpa menyimpan?")
          .setPositiveButton("Keluar") { _, _ -> finish() }.setNegativeButton("Tetap di sini", null).show()
      }
    }
    if (saveJob != null) afterSave = { ok -> if (modified) save(true, done) else done(ok) } else save(true, done)
  }

  override fun onDestroy() {
    super.onDestroy()
    if (::web.isInitialized) { web.removeJavascriptInterface("Android"); web.destroy() }
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (prefs.getBoolean("volKeys", false) && !searching) {
      if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { js("APP.next()"); return true }
      if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { js("APP.prev()"); return true }
    }
    return super.onKeyDown(keyCode, event)
  }

  // ======================================================================
  // Cari
  // ======================================================================
  private val findRunnable = Runnable { find(again = false, prev = false) }
  private fun openSearch() {
    searching = true; showBars(true)
    mainRow.visibility = View.GONE; searchRow.visibility = View.VISIBLE
    searchEt.requestFocus()
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchEt, 0)
  }
  private fun closeSearch() {
    searching = false
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchEt.windowToken, 0)
    searchRow.visibility = View.GONE; mainRow.visibility = View.VISIBLE
    findCount.text = ""
    js("APP.find('', false, false)")
  }
  private fun find(again: Boolean, prev: Boolean) {
    val t = searchEt.text.toString()
    if (t.isBlank()) { findCount.text = ""; js("APP.find('', false, false)"); return }
    js("APP.find(${q(t)}, $again, $prev)")
  }

  // ======================================================================
  // Penanda, daftar isi, daftar highlight
  // ======================================================================
  private fun toggleBookmark() {
    val ex = doc.bookmarks.firstOrNull { it.page == page }
    if (ex != null) { doc.bookmarks.remove(ex); toast("Penanda hal. $page dihapus", short = true) }
    else { doc.bookmarks.add(Bookmark(page, "", System.currentTimeMillis())); doc.bookmarks.sortBy { it.page }; toast("Hal. $page ditandai 🔖", short = true) }
    Library.save(this); onPageChanged(page)
  }

  class Row(val title: String, val sub: String, val page: Int, val indent: Int = 0, val color: Int = 0, val onLong: (() -> Unit)? = null)

  private fun openSheet(tabIndex: Int) {
    val dlg = BottomSheetDialog(this)
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
    val tabs = TabLayout(this).apply {
      addTab(newTab().setText("Daftar isi")); addTab(newTab().setText("Highlight")); addTab(newTab().setText("Penanda"))
      tabMode = TabLayout.MODE_FIXED
    }
    box.addView(tabs)
    val actions = LinearLayout(this).apply { gravity = Gravity.END; setPadding(dp(8), 0, dp(8), 0) }
    box.addView(actions)
    val info = TextView(this).apply { setTextColor(col(R.color.muted)); setPadding(dp(20), dp(16), dp(20), dp(16)); textSize = 14f }
    box.addView(info)
    val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ReaderActivity) }
    box.addView(rv, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.62).toInt()))
    val adapter = RowAdapter { r -> js("APP.goto(${r.page})"); dlg.dismiss() }
    rv.adapter = adapter

    fun show(rows: List<Row>, emptyText: String) {
      adapter.set(rows)
      info.text = emptyText; info.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }
    fun load(t: Int) {
      actions.removeAllViews()
      when (t) {
        0 -> { info.text = "Memuat…"; info.visibility = View.VISIBLE; adapter.set(emptyList())
          call("outline") { json ->
            val a = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
            show((0 until a.length()).map { a.getJSONObject(it) }.map { Row(it.optString("t"), if (it.optInt("p") > 0) "${it.optInt("p")}" else "", it.optInt("p"), it.optInt("d")) }.filter { it.page > 0 },
              "PDF ini tidak punya daftar isi.")
          } }
        1 -> { info.text = "Mencari highlight di semua halaman…"; info.visibility = View.VISIBLE; adapter.set(emptyList())
          call("highlights") { json ->
            val a = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
            val items = (0 until a.length()).map { a.getJSONObject(it) }
            doc.highlights = items.size; Library.save(this)
            show(items.map { o ->
              val note = o.optString("n")
              Row(o.optString("s"), "Hal. ${o.optInt("p")}" + (if (note.isNotBlank()) "  ·  📝 $note" else "") + (if (!o.optBoolean("saved")) "  ·  belum disimpan" else ""), o.optInt("p"), 0, parseColor(o.optString("c")))
            }, "Belum ada highlight.\n\nTahan pada tulisan sampai terpilih, lalu ketuk salah satu warna.")
            if (items.isNotEmpty()) {
              actions.addView(textBtn("Salin semua") { copyText(exportHighlights(items)); toast("Semua highlight disalin", short = true) })
              actions.addView(textBtn("Bagikan") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exportHighlights(items)), "Bagikan highlight")) })
            }
          } }
        2 -> {
          show(doc.bookmarks.map { b ->
            Row(b.label.ifBlank { "Halaman ${b.page}" }, "Hal. ${b.page}", b.page, onLong = { bookmarkMenu(b) { load(2) } })
          }, "Belum ada penanda.\n\nKetuk ikon 🔖 di bar atas untuk menandai halaman yang sedang dibaca.")
          actions.addView(textBtn(if (doc.bookmarks.any { it.page == page }) "Hapus penanda hal. $page" else "+ Tandai hal. $page") { toggleBookmark(); load(2) })
        }
      }
    }
    tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
      override fun onTabSelected(t: TabLayout.Tab) { prefs.edit().putInt("sheetTab", t.position).apply(); load(t.position) }
      override fun onTabUnselected(t: TabLayout.Tab) {}
      override fun onTabReselected(t: TabLayout.Tab) {}
    })
    val start = if (tabIndex >= 0) tabIndex else prefs.getInt("sheetTab", 0)
    if (start == 0) load(0) else tabs.getTabAt(start)?.select()
    dlg.setContentView(box)
    dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dlg.show()
  }

  private fun exportHighlights(items: List<JSONObject>): String {
    val sb = StringBuilder("📄 ${doc.name.removeSuffix(".pdf")}\n")
    for (o in items) {
      sb.append("\nHal. ${o.optInt("p")}: “${o.optString("s")}”")
      o.optString("n").takeIf { it.isNotBlank() }?.let { sb.append("\n   📝 $it") }
    }
    return sb.toString()
  }

  private fun bookmarkMenu(b: Bookmark, refresh: () -> Unit) {
    MaterialAlertDialogBuilder(this).setTitle("Penanda hal. ${b.page}")
      .setItems(arrayOf("Ganti nama", "Hapus")) { _, w ->
        if (w == 0) {
          val et = EditText(this).apply { setText(b.label); hint = "mis. Bab 3 – Jaringan" }
          MaterialAlertDialogBuilder(this).setTitle("Nama penanda").setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("Simpan") { _, _ -> b.label = et.text.toString().trim(); Library.save(this); refresh() }
            .setNegativeButton("Batal", null).show()
        } else { doc.bookmarks.remove(b); Library.save(this); onPageChanged(page); refresh() }
      }.show()
  }

  inner class RowAdapter(val onClick: (Row) -> Unit) : RecyclerView.Adapter<RowAdapter.VH>() {
    private var rows: List<Row> = emptyList()
    fun set(r: List<Row>) { rows = r; notifyDataSetChanged() }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LinearLayout(p.context))
    override fun onBindViewHolder(h: VH, i: Int) = h.bind(rows[i])
    inner class VH(val v: LinearLayout) : RecyclerView.ViewHolder(v) {
      val bar = View(v.context)
      val t = TextView(v.context)
      val s = TextView(v.context)
      init {
        v.layoutParams = RecyclerView.LayoutParams(-1, -2)
        v.gravity = Gravity.CENTER_VERTICAL
        val tv = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); v.setBackgroundResource(tv.resourceId)
        v.addView(bar, LinearLayout.LayoutParams(dp(5), -1).apply { setMargins(0, dp(6), dp(12), dp(6)) })
        val c = LinearLayout(v.context).apply { orientation = LinearLayout.VERTICAL }
        t.setTextColor(col(R.color.text)); t.textSize = 15f; t.maxLines = 4
        s.setTextColor(col(R.color.muted)); s.textSize = 12.5f; s.setPadding(0, dp(2), 0, 0)
        c.addView(t); c.addView(s)
        v.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
      }
      fun bind(r: Row) {
        v.setPadding(dp(16) + dp(16) * r.indent.coerceAtMost(5), dp(10), dp(16), dp(10))
        t.text = r.title; s.text = r.sub
        t.setTypeface(null, if (r.indent == 0 && r.color == 0 && r.onLong == null) Typeface.BOLD else Typeface.NORMAL)
        bar.visibility = if (r.color != 0) View.VISIBLE else View.GONE
        bar.background = round(r.color, dpf(3f))
        v.setOnClickListener { onClick(r) }
        v.setOnLongClickListener { r.onLong?.invoke(); r.onLong != null }
      }
    }
  }

  // ======================================================================
  // Menu ⋮
  // ======================================================================
  private fun moreMenu() {
    val items = listOf(
      "Lompat ke halaman…", "Tema baca…", "Mode baca (gulir / slide)…", "Paskan lebar layar", "Paskan satu halaman", "Putar halaman",
      "Simpan highlight sekarang", "Bagikan PDF", "Bagikan semua highlight",
      (if (prefs.getBoolean("keepOn", true)) "☑" else "☐") + " Layar tetap menyala",
      (if (prefs.getBoolean("volKeys", false)) "☑" else "☐") + " Tombol volume ganti halaman",
      (if (prefs.getBoolean("autosave", true)) "☑" else "☐") + " Simpan highlight otomatis",
      "Info dokumen",
    )
    MaterialAlertDialogBuilder(this).setItems(items.toTypedArray()) { _, w ->
      when (w) {
        0 -> gotoDialog()
        1 -> themeDialog()
        2 -> scrollDialog()
        3 -> js("APP.zoom('page-width')")
        4 -> js("APP.zoom('page-fit')")
        5 -> js("APP.rotate(90)")
        6 -> if (modified) save(true) { ok -> if (ok) toast("Tersimpan ✔", short = true) } else toast("Tidak ada perubahan", short = true)
        7 -> sharePdf()
        8 -> call("highlights") { json ->
          val a = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
          if (a.length() == 0) toast("Belum ada highlight") else
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exportHighlights((0 until a.length()).map { a.getJSONObject(it) })), "Bagikan highlight"))
        }
        9 -> { val v = !prefs.getBoolean("keepOn", true); prefs.edit().putBoolean("keepOn", v).apply()
          if (v) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        10 -> { val v = !prefs.getBoolean("volKeys", false); prefs.edit().putBoolean("volKeys", v).apply(); toast(if (v) "Volume − = halaman berikutnya, Volume + = sebelumnya" else "Tombol volume normal", short = true) }
        11 -> { val v = !prefs.getBoolean("autosave", true); prefs.edit().putBoolean("autosave", v).apply()
          toast(if (v) "Highlight disimpan otomatis ke file" else "Highlight disimpan saat keluar / lewat menu", short = true) }
        12 -> MaterialAlertDialogBuilder(this).setTitle(doc.name).setMessage(
          "Halaman: $page / $pages\nPenanda: ${doc.bookmarks.size}\n" +
            (if (doc.copy) "Lokasi: salinan di aplikasi\n" else "Lokasi: file asli (highlight ditulis ke file ini)\n") +
            "\nPDF dibaca dengan PDF.js (Mozilla, Apache-2.0).").setPositiveButton("OK", null).show()
      }
    }.show()
  }

  private fun sharePdf() {
    val go = {
      try {
        var u = Uri.parse(doc.uri)
        if (u.scheme == "file") u = FileProvider.getUriForFile(this, "$packageName.files", File(u.path!!))
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(doc.name, u)
        startActivity(Intent.createChooser(i, "Bagikan PDF"))
      } catch (e: Exception) { toast("Gagal: ${e.message}") }
    }
    if (modified) save(true) { go() } else go()
  }

  private fun gotoDialog() {
    if (pages == 0) return
    val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "1 – $pages"; setText(page.toString()); selectAll() }
    val d = MaterialAlertDialogBuilder(this).setTitle("Lompat ke halaman").setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
      .setPositiveButton("Buka") { _, _ -> et.text.toString().toIntOrNull()?.let { js("APP.goto($it)") } }
      .setNegativeButton("Batal", null).show()
    et.requestFocus()
    d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
  }

  private fun themeDialog() {
    val keys = listOf("day", "night", "sepia", "gray")
    val names = arrayOf("Siang (putih)", "Malam (gelap)", "Sepia (nyaman di mata)", "Abu-abu")
    MaterialAlertDialogBuilder(this).setTitle("Tema baca").setSingleChoiceItems(names, keys.indexOf(prefs.getString("theme", "day")).coerceAtLeast(0)) { di, w ->
      prefs.edit().putString("theme", keys[w]).apply(); applyTheme(); di.dismiss()
    }.show()
  }
  private fun applyTheme() { js("APP.theme(${q(prefs.getString("theme", "day")!!)})") }

  private fun isSlide() = prefs.getInt("scroll", 0) == 3

  private fun scrollDialog() {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
    val opts = listOf(
      Triple("📖 Slide – geser per halaman", 3, 0),
      Triple("📖 Slide – dua halaman (tablet/landscape)", 3, 1),
      Triple("Gulir ke bawah (vertikal)", 0, 0),
      Triple("Gulir ke samping (horizontal)", 1, 0),
      Triple("Gulir dua halaman", 0, 1),
    )
    val cur = opts.indexOfFirst { it.second == prefs.getInt("scroll", 0) && it.third == prefs.getInt("spread", 0) }.coerceAtLeast(0)
    val group = android.widget.RadioGroup(this)
    opts.forEachIndexed { i, o -> group.addView(com.google.android.material.radiobutton.MaterialRadioButton(this).apply { id = 100 + i; text = o.first; textSize = 15f; minHeight = dp(46) }) }
    group.check(100 + cur)
    box.addView(group)
    box.addView(TextView(this).apply { text = "Pengaturan slide"; setTextColor(col(R.color.muted)); textSize = 13f; setPadding(dp(8), dp(14), 0, dp(2)) })
    val dirSw = com.google.android.material.materialswitch.MaterialSwitch(this).apply { text = "Geser atas–bawah (bukan kiri–kanan)"; isChecked = prefs.getString("slideDir", "h") == "v" }
    val animSw = com.google.android.material.materialswitch.MaterialSwitch(this).apply { text = "Animasi geser"; isChecked = prefs.getBoolean("slideAnim", true) }
    val edgeSw = com.google.android.material.materialswitch.MaterialSwitch(this).apply { text = "Ketuk tepi kiri/kanan untuk ganti halaman"; isChecked = prefs.getBoolean("edgeTap", true) }
    listOf(dirSw, animSw, edgeSw).forEach { box.addView(it, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(8), 0, dp(8), 0) }) }
    MaterialAlertDialogBuilder(this).setTitle("Mode baca").setView(android.widget.ScrollView(this).apply { addView(box) })
      .setPositiveButton("Terapkan") { _, _ ->
        val o = opts[(group.checkedRadioButtonId - 100).coerceIn(0, opts.size - 1)]
        prefs.edit().putInt("scroll", o.second).putInt("spread", o.third)
          .putString("slideDir", if (dirSw.isChecked) "v" else "h").putBoolean("slideAnim", animSw.isChecked).putBoolean("edgeTap", edgeSw.isChecked).apply()
        applyScroll(announce = true)
      }.setNegativeButton("Batal", null).show()
  }

  private fun toggleSlide() {
    val e = prefs.edit()
    if (isSlide()) e.putInt("scroll", 0).putInt("spread", 0) else e.putInt("scroll", 3).putInt("spread", 0)
    e.apply()
    applyScroll(announce = true)
  }

  private fun applyScroll(announce: Boolean = false) {
    val slide = isSlide()
    js("APP.layout(${prefs.getInt("scroll", 0)}, ${prefs.getInt("spread", 0)}, ${q(prefs.getString("slideDir", "h")!!)}, ${prefs.getBoolean("slideAnim", true)})")
    if (::slideBtn.isInitialized) {
      slideBtn.iconTint = ColorStateList.valueOf(col(if (slide) R.color.accent else R.color.text))
      slideBtn.backgroundTintList = ColorStateList.valueOf(if (slide) col(R.color.accentDark) else Color.TRANSPARENT)
    }
    if (announce) {
      if (slide) {
        val v = prefs.getString("slideDir", "h") == "v"
        toast(if (v) "Mode slide: geser ke atas/bawah untuk ganti halaman" else "Mode slide: geser ke kiri/kanan untuk ganti halaman", short = true)
        ui.postDelayed({ if (isSlide() && mode == M_NONE && !searching) showBars(false) }, 1400)
      } else toast("Mode gulir", short = true)
    }
  }

  // ======================================================================
  // util tampilan
  // ======================================================================
  private fun col(id: Int) = ContextCompat.getColor(this, id)
  private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }
  private fun parseColor(s: String) = try { Color.parseColor(s) } catch (_: Exception) { Color.YELLOW }
  private fun toast(s: String, short: Boolean = false) = Toast.makeText(this, s, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
  private fun copyText(t: String) = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF", t))

  private fun iconBtn(icon: Int, desc: String, on: (View) -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
    setIconResource(icon); contentDescription = desc; iconTint = ColorStateList.valueOf(col(R.color.text)); setOnClickListener { on(it) }
    androidx.appcompat.widget.TooltipCompat.setTooltipText(this, desc)
  }
  private fun toolBtn(icon: Int, label: String, on: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
    setIconResource(icon); text = label; textSize = 10.5f; iconGravity = MaterialButton.ICON_GRAVITY_TOP; iconPadding = 0
    iconTint = ColorStateList.valueOf(col(R.color.text)); setTextColor(col(R.color.muted))
    strokeWidth = 0; insetTop = 0; insetBottom = 0; minHeight = dp(52); setPadding(0, dp(4), 0, dp(4)); isAllCaps = false
    cornerRadius = dp(12)
    setOnClickListener { on() }
  }
  private fun chip(icon: Int, label: String, on: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
    setIconResource(icon); text = label; textSize = 12f; isAllCaps = false; iconTint = ColorStateList.valueOf(col(R.color.text)); setTextColor(col(R.color.text))
    iconSize = dp(18); minHeight = dp(40); insetTop = 0; insetBottom = 0; setPadding(dp(10), 0, dp(12), 0)
    layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(dp(4), 0, dp(4), 0) }
    setOnClickListener { on() }
  }
  private fun textBtn(label: String, on: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
    text = label; isAllCaps = false; setOnClickListener { on() }
  }
  private fun colorDot(hex: String, name: String, selected: Boolean, on: () -> Unit) = View(this).apply {
    contentDescription = name
    background = GradientDrawable().apply {
      shape = GradientDrawable.OVAL; setColor(Color.parseColor(hex))
      setStroke(if (selected) dp(3) else dp(1), if (selected) Color.WHITE else Color.parseColor("#55000000"))
    }
    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(5), dp(2), dp(5), dp(2)) }
    setOnClickListener { on() }
  }
}

/**
 * WebView dengan menu seleksi teks tambahan (Highlight / Catatan) di samping Salin, Bagikan, dll.
 */
class PdfWebView(ctx: Context) : WebView(ctx) {
  var onHighlight: ((String, Boolean) -> Unit)? = null
  private var current: ActionMode? = null

  override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
    super.startActionMode(Wrap(callback), type).also { current = it }
  override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
    super.startActionMode(Wrap(callback)).also { current = it }

  fun finishActionMode() { try { current?.finish() } catch (_: Exception) {}; current = null }

  private inner class Wrap(val base: ActionMode.Callback) : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
      val r = base.onCreateActionMode(mode, menu)
      menu.add(Menu.NONE, 0x7f0b0001, 0, "🖍 Highlight").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
      menu.add(Menu.NONE, 0x7f0b0002, 1, "📝 Catatan").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
      return r
    }
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = base.onPrepareActionMode(mode, menu)
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
      val color = context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("hlColor", "#FFFF98")!!
      return when (item.itemId) {
        0x7f0b0001 -> { onHighlight?.invoke(color, false); true }
        0x7f0b0002 -> { onHighlight?.invoke(color, true); true }
        else -> base.onActionItemClicked(mode, item)
      }
    }
    override fun onDestroyActionMode(mode: ActionMode) { base.onDestroyActionMode(mode); if (current === mode) current = null }
    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
      if (base is ActionMode.Callback2) base.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
    }
  }
}
