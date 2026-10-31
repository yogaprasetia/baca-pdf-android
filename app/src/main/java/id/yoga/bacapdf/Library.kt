package id.yoga.bacapdf

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

class Bookmark(val page: Int, var label: String, val time: Long)

class Doc(
  var id: String,
  var uri: String,
  var name: String,
  var size: Long = 0,
  var pages: Int = 0,
  var page: Int = 1,
  var hash: String = "",
  var lastOpened: Long = 0,
  var added: Long = System.currentTimeMillis(),
  var fingerprint: String = "",
  var copy: Boolean = false,
  var highlights: Int = 0,
  val bookmarks: MutableList<Bookmark> = mutableListOf(),
) {
  val progress: Int get() = if (pages > 0) ((page * 100f) / pages).toInt().coerceIn(0, 100) else 0

  fun toJson() = JSONObject().apply {
    put("id", id); put("uri", uri); put("name", name); put("size", size); put("pages", pages); put("page", page)
    put("hash", hash); put("lastOpened", lastOpened); put("added", added); put("fingerprint", fingerprint); put("copy", copy)
    put("highlights", highlights)
    put("bookmarks", JSONArray().apply { bookmarks.forEach { put(JSONObject().put("page", it.page).put("label", it.label).put("time", it.time)) } })
  }

  companion object {
    fun from(o: JSONObject) = Doc(
      o.getString("id"), o.getString("uri"), o.optString("name", "PDF"), o.optLong("size"), o.optInt("pages"), o.optInt("page", 1),
      o.optString("hash"), o.optLong("lastOpened"), o.optLong("added"), o.optString("fingerprint"), o.optBoolean("copy"), o.optInt("highlights"),
    ).also { d ->
      val b = o.optJSONArray("bookmarks") ?: JSONArray()
      for (i in 0 until b.length()) b.getJSONObject(i).let { d.bookmarks.add(Bookmark(it.getInt("page"), it.optString("label"), it.optLong("time"))) }
    }
  }
}

/** Daftar PDF + posisi baca, disimpan sebagai JSON di penyimpanan aplikasi. */
object Library {
  private val docs = LinkedHashMap<String, Doc>()
  private var loaded = false
  private val io = Executors.newSingleThreadExecutor()

  private fun file(ctx: Context) = File(ctx.filesDir, "library.json")

  @Synchronized fun load(ctx: Context) {
    if (loaded) return
    loaded = true
    try {
      val arr = JSONArray(file(ctx).readText())
      for (i in 0 until arr.length()) Doc.from(arr.getJSONObject(i)).let { docs[it.id] = it }
    } catch (_: Exception) {}
  }

  @Synchronized fun all(): List<Doc> = docs.values.toList()
  @Synchronized fun get(id: String) = docs[id]
  @Synchronized fun put(d: Doc) { docs[d.id] = d }
  @Synchronized fun remove(ctx: Context, id: String) {
    val d = docs.remove(id) ?: return
    if (d.copy) try { File(Uri.parse(d.uri).path ?: "").delete() } catch (_: Exception) {}
    File(ctx.cacheDir, "thumbs/$id.png").delete()
    save(ctx)
  }

  fun save(ctx: Context) {
    val json = synchronized(this) { JSONArray().apply { docs.values.forEach { put(it.toJson()) } }.toString() }
    val app = ctx.applicationContext
    io.execute {
      try {
        val f = file(app); val tmp = File(f.path + ".tmp")
        tmp.writeText(json); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
      } catch (_: Exception) {}
    }
  }

  fun idFor(uri: String): String {
    val md = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
    return md.joinToString("") { "%02x".format(it) }.take(16)
  }
}

object Pdf {
  private val main = Handler(Looper.getMainLooper())
  private val pool = Executors.newFixedThreadPool(2)

  fun nameAndSize(ctx: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Dokumen.pdf"
    var size = -1L
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
      try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
          if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
          }
        }
      } catch (_: Exception) {}
    } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
      size = File(uri.path ?: "").length()
    }
    return name to size
  }

  /**
   * Masukkan PDF ke perpustakaan. URI dari pemilih file (SAF) disimpan izinnya agar bisa
   * dibuka lagi & ditulisi highlight. URI sementara (WhatsApp, Gmail, dll) disalin ke aplikasi.
   */
  fun import(act: Activity, uri: Uri, fromPicker: Boolean, done: (Doc?, String?) -> Unit) {
    pool.execute {
      try {
        val ctx = act.applicationContext
        Library.load(ctx)
        var useUri = uri
        var copy = false
        val (name, size) = nameAndSize(ctx, uri)
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
          var persisted = false
          val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
          try { ctx.contentResolver.takePersistableUriPermission(uri, rw); persisted = true } catch (_: Exception) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); persisted = true } catch (_: Exception) {}
          }
          if (!persisted) {
            // sudah ada di perpustakaan sebagai salinan dengan nama & ukuran sama → pakai itu
            Library.all().firstOrNull { it.copy && it.name == name && size > 0 && it.size == size }?.let { d ->
              main.post { done(d, null) }; return@execute
            }
            val dir = File(ctx.filesDir, "docs").apply { mkdirs() }
            var f = File(dir, safeName(name))
            var i = 2
            while (f.exists()) { f = File(dir, safeName(name).removeSuffix(".pdf") + " ($i).pdf"); i++ }
            ctx.contentResolver.openInputStream(uri)!!.use { inp -> FileOutputStream(f).use { inp.copyTo(it, 1 shl 16) } }
            useUri = Uri.fromFile(f); copy = true
          }
        }
        val key = useUri.toString()
        val id = Library.idFor(key)
        val d = Library.get(id) ?: Doc(id, key, name, if (copy) File(useUri.path!!).length() else size, copy = copy)
        d.name = name
        if (size > 0 && !copy) d.size = size
        d.lastOpened = System.currentTimeMillis()
        Library.put(d); Library.save(ctx)
        main.post { done(d, null) }
      } catch (e: Exception) {
        main.post { done(null, e.message ?: e.javaClass.simpleName) }
      }
    }
  }

  private fun safeName(n: String): String {
    val s = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
    return if (s.lowercase().endsWith(".pdf")) s else "$s.pdf"
  }

  fun canRead(ctx: Context, d: Doc): Boolean = try {
    val u = Uri.parse(d.uri)
    if (u.scheme == ContentResolver.SCHEME_FILE) File(u.path!!).canRead()
    else ctx.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
  } catch (_: Exception) { false }

  /** Thumbnail halaman pertama (PdfRenderer bawaan Android), di-cache sebagai PNG. */
  fun thumb(ctx: Context, d: Doc, widthPx: Int, cb: (Bitmap?) -> Unit) {
    val cache = File(ctx.cacheDir, "thumbs/${d.id}.png")
    pool.execute {
      var bmp: Bitmap? = null
      try {
        if (cache.exists()) bmp = BitmapFactory.decodeFile(cache.path)
        if (bmp == null) {
          val u = Uri.parse(d.uri)
          val pfd: ParcelFileDescriptor? = if (u.scheme == ContentResolver.SCHEME_FILE)
            ParcelFileDescriptor.open(File(u.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
          else ctx.contentResolver.openFileDescriptor(u, "r")
          pfd?.use { fd ->
            PdfRenderer(fd).use { r ->
              if (r.pageCount > 0) r.openPage(0).use { p ->
                val h = (widthPx.toFloat() * p.height / p.width).toInt().coerceIn(1, widthPx * 2)
                val b = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp = b
                cache.parentFile?.mkdirs()
                FileOutputStream(cache).use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
              }
            }
          }
        }
      } catch (_: Throwable) {}
      main.post { cb(bmp) }
    }
  }

  fun dropThumb(ctx: Context, id: String) { File(ctx.cacheDir, "thumbs/$id.png").delete() }
}
