# Baca PDF (Android)

Pembaca PDF dengan highlight, catatan, dan posisi baca tersimpan otomatis.
Mesin tampilan: PDF.js 6.4.299 (Mozilla, Apache-2.0) di WebView, UI native Kotlin + Material 3.

Fitur
- Perpustakaan: daftar PDF + thumbnail, progres baca (hal x/y, %), cari, urutkan
- Buka dari pemilih file, atau dari WhatsApp/Gmail/File Manager (Buka dengan / Bagikan ke Baca PDF)
- Halaman & posisi zoom terakhir tersimpan otomatis per file
- Highlight: tahan teks → pilih warna (kuning/hijau/biru/pink/merah), atau "Highlight + catatan"
- Stabilo bebas, pena (coretan), kotak teks; urungkan/ulangi; hapus anotasi
- Highlight ditulis ke file PDF asli (terbaca di aplikasi PDF lain); bila file read-only → salinan di aplikasi
- Daftar highlight (teks + halaman + catatan), salin/bagikan semua highlight sebagai teks
- Daftar isi (outline PDF), penanda halaman (bisa diberi nama)
- Cari teks di dokumen, lompat ke halaman, slider halaman
- Tema baca: siang, malam, sepia, abu-abu
- Mode slide: geser per halaman dengan animasi (kiri-kanan atau atas-bawah), dua halaman, ketuk tepi, tombol volume; halaman di-zoom tetap bisa digeser, ganti halaman saat sampai tepi
- Mode gulir vertikal / horizontal / dua halaman
- Tombol volume ganti halaman, layar tetap menyala, layar penuh (ketuk tengah)

Build
  export BACAPDF_KS_PASS=... ANDROID_HOME=...
  ./gradlew assembleRelease lintRelease
