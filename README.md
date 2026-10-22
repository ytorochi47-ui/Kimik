# WebtoonLite v0.2 – OCR Komik (PP-OCRv5 CPU)

Optimasi untuk Android RAM 4 GB, tangkap area dialog **atas permintaan**, OCR lokal **PP-OCRv5 mobile** dan fallback ML Kit, kirim **teks saja** ke Gemini untuk terjemahan. Tidak ada Gemini Vision / token gambar.

## Pembuatan APK otomatis (gratis)

1. Upload **isi folder WebtoonLite** ke GitHub privat. Jangan upload API key.
2. Pastikan `.github/workflows/build-android-apk.yml` ikut diupload. Untuk file tersembunyi buat langsung dari **Add file → Create new file**.
3. Buka **Actions → Build Android APK (Free)** dan jalankan `Run workflow`.
4. Kalau workflow hijau, unduh artifact `WebtoonLite-Android11-APK`, ekstrak dan install `WebtoonLite-debug.apk`.

**Perubahan v0.2:** saat build, script `scripts/prepare_native_ocr.py` mengunduh PP-OCRv5 Mobile `ppocrv5.cpp/.h`, dictionary dan empat file model dari revisi upstream yang dipin, serta ncnn CPU dan OpenCV Mobile. Semua itu terjadi dalam GitHub Actions, tanpa harus memasukkan model berukuran besar ke repository GitHub secara manual. Butuh jaringan CI untuk mengunduhnya.

**Catatan kompatibilitas:** saat ini paket ditargetkan ke `arm64-v8a` (perlu Android OS 64-bit; chipset 64-bit saja belum cukup). Jika HP menggunakan OS 32-bit, APK ini tidak bisa dipasang. Min Android API 26, maksimal target SDK 34. Backend CPU-only. APK dapat lebih besar daripada v0.1 karena dua native library serta bobot model (~10.6 MB).

**Cakupan:** untuk dialog Inggris horizontal, bukan aksara Korea, efek suara sangat artistik, maupun panel penuh. Jangan menganggap target angka tertentu adalah jaminan OCR komik.

## Tahapan runtime

T → pilih kotak → hanya crop terpilih → OCR lokal PP-OCRv5 → fallback ML Kit jika kosong → Gemini menerima teks → popup Indonesia. Model OCR dimuat ketika pertama digunakan, dan pemrosesan dilakukan di thread terpisah.

## Source pihak ketiga

- https://github.com/nihui/ncnn-android-ppocrv5 (PP-OCRv5 Android, kode native mengikuti ketentuan BSD-3-Clause)
- https://github.com/Tencent/ncnn (BSD-3-Clause)
- https://github.com/nihui/opencv-mobile (OpenCV, lisensi sesuai upstream)
- https://github.com/PaddlePaddle/PaddleOCR

## Status verifikasi

Source ini belum dijalankan melalui Android Gradle build di lingkungan pembuatan file ini; kompilasi dan pengujian fisik perlu dilakukan via workflow GitHub Actions. Jika workflow gagal, lihat langkah merah dan log-nya. Tidak ada API key Gemini dalam file proyek.
