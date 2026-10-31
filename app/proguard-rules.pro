# Antarmuka JavaScript dipanggil dari WebView lewat refleksi
-keepclassmembers class id.yoga.bacapdf.ReaderActivity$Bridge {
  @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
