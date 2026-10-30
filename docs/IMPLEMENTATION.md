# Kimik 0.6 â€” catatan implementasi

## Analisis awal

Home sebelumnya berisi input key, instruksi panjang, dan mode kamera. Branding masih Webtoon Lite. Enkripsi Keystore sudah benar dan formatnya perlu dipertahankan. Filter realtime hanya menerima Latin, dan fallback hanya mengenali Latin. Pipeline realtime sudah memiliki debounce, cache dan pembatalan request sehingga dipertahankan.

## Perubahan file

- `MainActivity`, `SettingsActivity`, `UiStyle`: single-column UI, status layanan aktual lewat broadcast internal, izin, settings terpisah dan micro-animation.
- `AppPreferences`, `SecureKeyStore`: preferensi nonrahasia, pemuatan ulang key, penulisan enkripsi yang melaporkan kegagalan, hapus key.
- `OcrTranslator`, `MlKitBoxes`, `RealtimeEngine`, `DialogFilter`, `GeminiTranslator`: OCR Korea/Latin, filter Unicode, prompt manhwa, pembatasan ukuran gambar dan pelepasan recognizer.
- `ScreenTranslatorService`: nama Kimik dan status layanan untuk home.
- `AndroidManifest.xml`, `app/build.gradle`: hapus kamera/CameraX, daftarkan settings, tambah Korean OCR dan icon; package name tetap.
- `res/`: vector capybara memegang HP, adaptive launcher icon beige, fade 180 ms.
- Tests dan workflow: regression Hangul, persistensi key, nama artifact Kimik.

## Keputusan

Menggunakan framework Material Views yang sudah tersedia menghindari migrasi UI/dependency tambahan. Vector dipakai bersama home dan launcher agar branding konsisten. Otomatis dan Korea menggunakan satu recognizer Korea (juga mendukung Latin); mode Inggris mempertahankan native. Mengubah preferensi mengakhiri sesi supaya hasil cache dan request tidak memakai konfigurasi lama. Gambar tidak diunggah.

## Checklist perangkat (belum dijalankan)

- Pasang sebagai update; pastikan key versi sebelumnya terbaca.
- Simpan key, force-stop/buka ulang, pastikan status AI dan preferensi kembali.
- Ganti/hapus key; cek pembatalan sesi aktif dan key tidak muncul di home/recents.
- Tolak/beri izin overlay dan tangkap layar; hentikan capture dari Android; cek status dan tombol home.
- Uji T dan R pada panel Inggris, Korea, campuran, dialog pendek, dan teks kecil.
- Uji offline sebelum dan sesudah unduh model; pastikan pesan dapat ditindaklanjuti.
- Uji rotasi, font besar, TalkBack, icon launcher mask bulat/kotak.
- Ukur RAM/latensi pada Vivo Y20 sebelum/saat/sesudah realtime; periksa pelepasan setelah idle.

## Hasil validasi lokal

- 31 unit test JUnit lulus (CropMathTest + RealtimeLogicTest), dikompilasi dengan Eclipse ECJ dan dijalankan pada runtime Java lokal.
- 34 file Java lolos parser sintaks Eclipse; seluruh XML well-formed dan `git diff --check` bersih.
- Preview icon dirender dari path vector yang sama dan diperiksa visual: [preview](branding-preview.png).
- Build APK, Android lint, instrumentation test, OCR Korea end-to-end dan pengukuran Vivo belum dijalankan: Android SDK/NDK tidak tersedia di mesin ini. Parser bukan pengganti kompilasi Android.
- Workflow smoke test kini memeriksa exit code Gradle, sehingga kegagalan test persistensi tidak tertutup oleh marker sukses OCR.
- Validasi di atas dilakukan pada checkout lokal sebelum push; hasil build Android tersedia melalui GitHub Actions.
