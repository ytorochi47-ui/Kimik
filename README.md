# WebtoonLite v0.3 – OCR komik lokal (PP-OCRv5) + terjemahan Gemini

Penerjemah webtoon Inggris → Indonesia untuk Android 8+ (diuji target: vivo Y20 2021, Android 11, RAM 4 GB). Biaya Rp0: OCR berjalan di perangkat, hanya **teks** hasil OCR yang dikirim ke Gemini (tidak ada Gemini Vision, tidak ada gambar yang diunggah).

## Alur runtime

Ketuk **T** → layar ditangkap sekali → seret kotak di sekitar dialog → hanya area itu di-crop → **PP-OCRv5 Mobile (ncnn, CPU)** → bila hasil kosong/kurang yakin (confidence < 0,55 atau tak ada kata) **ML Kit** sebagai cadangan → teks dikirim ke Gemini → terjemahan tampil di popup.

- Model PP-OCRv5 dimuat saat pertama dipakai dan **dilepas otomatis setelah 90 detik tanpa aktivitas** atau saat translator dihentikan (model aktif memakai >100 MB memori native).
- Crop kecil dinaikkan skalanya (≥ 480 px sisi terpanjang), crop besar dibatasi 960 px supaya RAM dan waktu tetap terkendali.
- Kotak teks pada baris yang sama digabung dengan spasi, antarbaris dengan baris baru.
- Permintaan Gemini ringkas: instruksi singkat, ≤ 2 dialog sebelumnya sebagai konteks, batas token keluaran mengikuti panjang teks, cache lokal untuk teks yang sama.

## Build APK (gratis, GitHub Actions)

1. Push ke `main` (atau jalankan **Actions → Build Android APK (Free) → Run workflow**).
2. Job `build` mengunduh model/library dengan **SHA-256 terpin** (`scripts/prepare_native_ocr.py`), mengompilasi APK `arm64-v8a` + `armeabi-v7a`, lalu memeriksa isi APK (library native kedua ABI ada, model tersimpan tanpa kompresi, tidak ada pola API key).
3. Job `smoke-test` menjalankan jalur native yang sama (assets → JNI → ncnn → PP-OCRv5) di emulator Android 11 x86_64 dan memeriksa hasil OCR.
4. Unduh artifact **WebtoonLite-Android11-APK** → `WebtoonLite-debug.apk`.

API key Gemini **tidak** ada di source/APK: dimasukkan lewat aplikasi dan disimpan terenkripsi (Android Keystore).

## Batasan yang perlu diketahui

- Dirancang untuk teks Inggris horizontal. Aksara non-Latin, efek suara sangat artistik, dan panel penuh bukan target.
- Akurasi pada font komik yang sangat dekoratif tidak bisa dijamin; uji otomatis hanya memakai font sistem Android (bersih, putih-di-gelap, bergaris tepi).
- Kecepatan di ponsel sungguhan belum diukur (smoke test berjalan di emulator x86_64).
- Alur tangkap layar/overlay (MediaProjection) membutuhkan interaksi pengguna dan tidak diuji otomatis.

## Pihak ketiga

- https://github.com/nihui/ncnn-android-ppocrv5 (model & kamus PP-OCRv5; logika deteksi/pengenalan diadaptasi di `webtoon_ocr.cpp`, BSD-3-Clause)
- https://github.com/Tencent/ncnn (BSD-3-Clause)
- https://github.com/nihui/opencv-mobile (lisensi sesuai upstream)
- https://github.com/PaddlePaddle/PaddleOCR
