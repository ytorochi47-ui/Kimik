# Kimik

Penerjemah dialog Inggris → Indonesia dan Korea → Indonesia, Android 8+.
Java/Views, package `id.rexz.webtoonlite`. Ditujukan untuk vivo Y20 2021; konsumsi RAM dan latensi versi ini belum diukur pada perangkat tersebut.

## Penggunaan

1. Buka Pengaturan (gear), masukkan API key Gemini, lalu Simpan. Key dienkripsi AES-GCM dengan Android Keystore; alias dan format penyimpanan versi lama tetap dipakai.
2. Aktifkan **Gunakan AI tersimpan** dan pilih **Otomatis**, **Inggris**, atau **Korea**. Key terakhir otomatis dipakai kembali saat aplikasi dibuka. Status AI Aktif berarti key tersedia dan penggunaan AI diizinkan, bukan hasil uji koneksi.
3. Tekan **Mulai Translator**, berikan izin overlay dan tangkap layar. Tombol melayang **T** memilih area dialog screenshot; **R** menjalankan realtime.
4. Tekan **Hentikan Translator** untuk menutup sesi. Perubahan key, toggle AI, atau bahasa menghentikan sesi; mulai kembali agar seluruh cache/konteks memakai preferensi terbaru.

Pengaturan menyediakan Simpan, Ganti (masukkan key baru kemudian Simpan), dan Hapus dengan konfirmasi. Key tidak ditampilkan di home, tidak diisi ulang ke input, tidak masuk instance state/autofill, dan layar Pengaturan dilindungi dari screenshot. Mode kamera dan CameraX telah dihapus.

## OCR dan performa

- **Inggris:** PP-OCRv5 native, ML Kit Latin sebagai fallback. Realtime juga mencoba fallback jika native tidak menemukan kotak.
- **Korea/Otomatis:** ML Kit Korean yang mengenali Hangul dan Latin, langsung tanpa menjalankan native OCR terlebih dahulu. Ini menghindari teks Korea terlanjur dianggap sebagai huruf Latin oleh model native. Otomatis mengenali kedua aksara menggunakan satu recognizer, bukan dua OCR berurutan.
- Model ML Kit tidak dibundel; Google Play Services mengunduhnya saat diperlukan (metadata `ocr,ocr_korean` meminta instalasi awal). Internet dan Google Play Services diperlukan untuk menyiapkan model; bila belum siap aplikasi meminta pengguna mencoba lagi. Tidak ada fallback Latin diam-diam untuk Korea.
- Hanya teks OCR dikirim ke Gemini, bukan screenshot. Prompt menjaga nama, emosi, nada bicara, dan honorifik yang relevan.
- Realtime tetap berbasis perubahan layar, debounce 500 ms, interval OCR minimal 1,2 detik, cache 200 entri dan batch API. Frame ML Kit dibatasi 1280 px dan koordinat kotak dikembalikan ke ukuran layar.
- Model dilepas setelah idle 90 detik atau saat sesi dihentikan; recognizer realtime ditutup pada worker agar UI tidak menunggu pekerjaan OCR.
- Branding capybara memakai vector Android; animasi framework singkat 140–180 ms, tanpa loop atau library animasi tambahan.

Akurasi font dekoratif dan teks sangat kecil tetap terbatas. Untuk dialog sulit gunakan T dan pilih crop lebih rapat. Tidak ada klaim pengukuran performa perangkat sebelum pengujian langsung.

Dokumentasi OCR: https://developers.google.com/ml-kit/vision/text-recognition/v2/android

## Build dan validasi

GitHub Actions **Build Android APK (Free)** menyiapkan Java 17, Android SDK 35, NDK dan CMake serta model native dengan checksum terpin. Menjalankan unit test, membangun APK ARM 32/64-bit dan smoke test native pada emulator Android 11. Artifact: **Kimik-Android11-APK**, file **Kimik-debug.apk**.

Unit test mencakup crop, grouping, filter Hangul/Latin, noise/URL, cache/batch dan deteksi perubahan layar. Instrumentation test `SettingsPersistenceTest` memeriksa enkripsi, pemuatan ulang, penggantian, penghapusan key dan toggle AI. Pengujian OCR Korea memerlukan model Google Play Services yang sudah diunduh; hasilnya belum diukur pada vivo.

Lihat [catatan implementasi dan checklist perangkat](docs/IMPLEMENTATION.md).

## Pihak ketiga

- https://github.com/nihui/ncnn-android-ppocrv5
- https://github.com/Tencent/ncnn
- https://github.com/nihui/opencv-mobile
- https://github.com/PaddlePaddle/PaddleOCR
