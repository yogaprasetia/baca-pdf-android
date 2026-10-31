package id.yoga.bacapdf

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.io.File

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

class MainActivity : AppCompatActivity() {
  private lateinit var list: RecyclerView
  private lateinit var empty: View
  private lateinit var search: EditText
  private lateinit var searchBox: View
  private val adapter = DocAdapter()
  private var query = ""
  private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

  private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    if (uris.isNullOrEmpty()) return@registerForActivityResult
    if (uris.size == 1) openUri(uris[0], true)
    else uris.forEach { u -> Pdf.import(this, u, true) { _, err -> if (err != null) toast("Gagal: $err"); refresh() } }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Library.load(this)
    buildUi()
    handleIntent(intent)
  }

  override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
  override fun onResume() { super.onResume(); refresh() }

  private fun handleIntent(i: Intent?) {
    i ?: return
    val uri: Uri? = when (i.action) {
      Intent.ACTION_VIEW -> i.data
      Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
      else -> null
    }
    if (uri != null) { i.action = null; openUri(uri, false) }
  }

  private fun openUri(uri: Uri, fromPicker: Boolean) {
    val dlg = MaterialAlertDialogBuilder(this).setView(ProgressBar(this).apply { setPadding(0, dp(24), 0, dp(24)) }).setMessage("Membuka…").setCancelable(false).show()
    Pdf.import(this, uri, fromPicker) { d, err ->
      dlg.dismiss()
      if (d == null) { toast("Gagal membuka: $err"); return@import }
      openDoc(d)
    }
  }

  private fun openDoc(d: Doc) {
    if (!Pdf.canRead(this, d)) {
      MaterialAlertDialogBuilder(this).setTitle("File tidak bisa dibuka")
        .setMessage("\"${d.name}\" sudah dipindah, dihapus, atau izinnya hilang.\nBuka lagi lewat tombol Buka PDF.")
        .setPositiveButton("Hapus dari daftar") { _, _ -> Library.remove(this, d.id); refresh() }
        .setNegativeButton("Tutup", null).show()
      return
    }
    startActivity(Intent(this, ReaderActivity::class.java).putExtra("id", d.id))
  }

  // ---------------- tampilan ----------------
  private fun buildUi() {
    val root = FrameLayout(this)
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(col, FrameLayout.LayoutParams(-1, -1))

    // bar atas
    val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(4), dp(8)) }
    val title = TextView(this).apply {
      text = "Baca PDF"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(c(R.color.text))
    }
    bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
    bar.addView(iconBtn(R.drawable.ic_search, "Cari") { toggleSearch() })
    bar.addView(iconBtn(R.drawable.ic_sort, "Urutkan") { v -> sortMenu(v) })
    col.addView(bar)

    searchBox = LinearLayout(this).apply {
      gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(12), 0, dp(12), dp(8))
      search = EditText(this@MainActivity).apply {
        hint = "Cari judul PDF…"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
        background = round(c(R.color.surface2), dpf(12f)); setPadding(dp(14), dp(10), dp(14), dp(10)); setTextColor(c(R.color.text)); setHintTextColor(c(R.color.muted))
        addTextChangedListener(object : TextWatcher {
          override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
          override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
          override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; refresh() }
        })
      }
      addView(search, LinearLayout.LayoutParams(-1, -2))
    }
    col.addView(searchBox)

    list = RecyclerView(this).apply {
      layoutManager = LinearLayoutManager(this@MainActivity)
      adapter = this@MainActivity.adapter
      clipToPadding = false
      setPadding(dp(12), dp(4), dp(12), dp(96))
    }
    col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

    empty = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(120))
      addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_launcher_fg) }, LinearLayout.LayoutParams(dp(140), dp(140)))
      addView(TextView(this@MainActivity).apply {
        text = "Belum ada PDF"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(c(R.color.text)); gravity = Gravity.CENTER
      })
      addView(TextView(this@MainActivity).apply {
        text = "Ketuk Buka PDF, atau buka file PDF dari WhatsApp / File Manager lalu pilih Baca PDF.\n\nTahan teks untuk highlight. Halaman terakhir tersimpan otomatis."
        textSize = 14f; setTextColor(c(R.color.muted)); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
      })
    }
    root.addView(empty, FrameLayout.LayoutParams(-1, -1))

    val fab = ExtendedFloatingActionButton(this).apply {
      text = "Buka PDF"; setIconResource(R.drawable.ic_folder)
      setOnClickListener { picker.launch(arrayOf("application/pdf")) }
    }
    root.addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(20), dp(24)) })

    ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
      val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(b.left, b.top, b.right, b.bottom); ins
    }
    setContentView(root)
  }

  private fun toggleSearch() {
    if (searchBox.visibility == View.VISIBLE) { searchBox.visibility = View.GONE; search.setText("") }
    else { searchBox.visibility = View.VISIBLE; search.requestFocus() }
  }

  private fun sortMenu(v: View) {
    val m = PopupMenu(this, v)
    val cur = prefs.getString("sort", "recent")
    listOf("recent" to "Terakhir dibaca", "name" to "Nama", "added" to "Baru ditambahkan", "progress" to "Progres baca").forEachIndexed { i, (k, t) ->
      m.menu.add(0, i, i, t).apply { isCheckable = true; isChecked = k == cur }
    }
    m.setOnMenuItemClickListener { prefs.edit().putString("sort", listOf("recent", "name", "added", "progress")[it.itemId]).apply(); refresh(); true }
    m.show()
  }

  private fun refresh() {
    var docs = Library.all()
    if (query.isNotBlank()) docs = docs.filter { it.name.contains(query, true) }
    docs = when (prefs.getString("sort", "recent")) {
      "name" -> docs.sortedBy { it.name.lowercase() }
      "added" -> docs.sortedByDescending { it.added }
      "progress" -> docs.sortedByDescending { it.progress }
      else -> docs.sortedByDescending { it.lastOpened }
    }
    adapter.submit(docs)
    empty.visibility = if (Library.all().isEmpty()) View.VISIBLE else View.GONE
  }

  private fun docMenu(v: View, d: Doc) {
    val m = PopupMenu(this, v)
    m.menu.add(0, 1, 0, "Buka")
    m.menu.add(0, 2, 1, "Bagikan PDF")
    m.menu.add(0, 3, 2, "Info")
    m.menu.add(0, 4, 3, "Mulai dari awal")
    m.menu.add(0, 5, 4, "Hapus dari daftar")
    m.setOnMenuItemClickListener {
      when (it.itemId) {
        1 -> openDoc(d)
        2 -> share(d)
        3 -> info(d)
        4 -> { d.page = 1; d.hash = ""; Library.save(this); refresh() }
        5 -> MaterialAlertDialogBuilder(this).setTitle("Hapus dari daftar?")
          .setMessage(if (d.copy) "\"${d.name}\" adalah salinan di aplikasi (beserta highlight-nya) dan akan ikut terhapus." else "File aslinya tidak dihapus.")
          .setPositiveButton("Hapus") { _, _ -> Library.remove(this, d.id); refresh() }.setNegativeButton("Batal", null).show()
      }
      true
    }
    m.show()
  }

  private fun share(d: Doc) {
    try {
      var u = Uri.parse(d.uri)
      if (u.scheme == "file") u = FileProvider.getUriForFile(this, "$packageName.files", File(u.path!!))
      val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      i.clipData = ClipData.newRawUri(d.name, u)
      startActivity(Intent.createChooser(i, "Bagikan ${d.name}"))
    } catch (e: Exception) { toast("Gagal: ${e.message}") }
  }

  private fun info(d: Doc) {
    val s = StringBuilder()
    s.append("Halaman: ${d.page} / ${if (d.pages > 0) d.pages else "?"} (${d.progress}%)\n")
    if (d.size > 0) s.append("Ukuran: ${Formatter.formatShortFileSize(this, d.size)}\n")
    s.append("Highlight: ${d.highlights}\nPenanda: ${d.bookmarks.size}\n")
    s.append("Terakhir dibaca: ${if (d.lastOpened > 0) DateUtils.getRelativeTimeSpanString(d.lastOpened) else "-"}\n")
    s.append(if (d.copy) "\nDisimpan sebagai salinan di aplikasi (file asli tidak bisa diubah)." else "\nHighlight disimpan langsung ke file aslinya.")
    MaterialAlertDialogBuilder(this).setTitle(d.name).setMessage(s.toString()).setPositiveButton("OK", null).show()
  }

  // ---------------- daftar ----------------
  inner class DocAdapter : RecyclerView.Adapter<DocAdapter.VH>() {
    private var items: List<Doc> = emptyList()
    fun submit(l: List<Doc>) { items = l; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LinearLayout(p.context))
    override fun onBindViewHolder(h: VH, i: Int) = h.bind(items[i])

    inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
      val thumb = ImageView(row.context)
      val name = TextView(row.context)
      val meta = TextView(row.context)
      val bar = ProgressBar(row.context, null, android.R.attr.progressBarStyleHorizontal)
      val more = iconBtn(R.drawable.ic_more, "Menu") {}
      var cur: Doc? = null

      init {
        row.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = round(c(R.color.surface), dpf(14f))
        row.setPadding(dp(10), dp(10), dp(2), dp(10))
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = round(Color.WHITE, dpf(6f)); thumb.clipToOutline = true
        row.addView(thumb, LinearLayout.LayoutParams(dp(54), dp(72)))
        val mid = LinearLayout(row.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        name.textSize = 15.5f; name.setTextColor(c(R.color.text)); name.setTypeface(name.typeface, Typeface.BOLD); name.maxLines = 2
        meta.textSize = 12.5f; meta.setTextColor(c(R.color.muted)); meta.setPadding(0, dp(3), 0, dp(6))
        bar.max = 100; bar.progressTintList = ColorStateList.valueOf(c(R.color.accent))
        bar.progressBackgroundTintList = ColorStateList.valueOf(c(R.color.line))
        mid.addView(name); mid.addView(meta); mid.addView(bar, LinearLayout.LayoutParams(-1, dp(4)))
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(more)
        row.setOnClickListener { cur?.let { openDoc(it) } }
        row.setOnLongClickListener { cur?.let { docMenu(more, it) }; true }
        more.setOnClickListener { cur?.let { docMenu(more, it) } }
      }

      fun bind(d: Doc) {
        cur = d
        name.text = d.name.removeSuffix(".pdf").removeSuffix(".PDF")
        val parts = mutableListOf<String>()
        parts += if (d.pages > 0) "Hal ${d.page}/${d.pages} · ${d.progress}%" else "Belum dibaca"
        if (d.highlights > 0) parts += "🖍 ${d.highlights}"
        if (d.bookmarks.isNotEmpty()) parts += "🔖 ${d.bookmarks.size}"
        if (d.lastOpened > 0) parts += DateUtils.getRelativeTimeSpanString(d.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        meta.text = parts.joinToString("  ·  ")
        bar.progress = d.progress
        thumb.setImageDrawable(null)
        val id = d.id
        Pdf.thumb(this@MainActivity, d, dp(108)) { b -> if (cur?.id == id) thumb.setImageBitmap(b) }
      }
    }
  }

  // ---------------- util ----------------
  private fun c(id: Int) = ContextCompat.getColor(this, id)
  private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }
  private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
  private fun iconBtn(icon: Int, desc: String, on: (View) -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
    setIconResource(icon); contentDescription = desc; iconTint = ColorStateList.valueOf(c(R.color.text))
    setOnClickListener { on(it) }
  }
}
