// JNI wrapper around WebtoonOcr. Third-party pieces (dictionary header, models,
// ncnn, OpenCV Mobile) are fetched by scripts/prepare_native_ocr.py during CI.
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <mutex>
#include <string>
#include <vector>
#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include "webtoon_ocr.h"

static std::mutex gMutex;
static WebtoonOcr* gEngine = nullptr;
static float gLastConf = 0.f;

static bool canOpen(AAssetManager* assets, const char* path) {
    AAsset* f = AAssetManager_open(assets, path, AASSET_MODE_UNKNOWN);
    if (!f) return false;
    AAsset_close(f);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_id_rexz_webtoonlite_NativePaddleOcr_nativeInitialize(JNIEnv* env, jclass, jobject manager) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gEngine) return JNI_TRUE;
    if (!manager) return JNI_FALSE;
    AAssetManager* assets = AAssetManager_fromJava(env, manager);
    if (!assets) return JNI_FALSE;
    const char* dp = "PP_OCRv5_mobile_det.ncnn.param";
    const char* db = "PP_OCRv5_mobile_det.ncnn.bin";
    const char* rp = "PP_OCRv5_mobile_rec.ncnn.param";
    const char* rb = "PP_OCRv5_mobile_rec.ncnn.bin";
    if (!canOpen(assets, dp) || !canOpen(assets, db) || !canOpen(assets, rp) || !canOpen(assets, rb))
        return JNI_FALSE;
    WebtoonOcr* e = new WebtoonOcr();
    if (e->load(assets, dp, db, rp, rb) != 0) {
        delete e;
        return JNI_FALSE;
    }
    gEngine = e;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_id_rexz_webtoonlite_NativePaddleOcr_nativeRelease(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    delete gEngine;
    gEngine = nullptr;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_id_rexz_webtoonlite_NativePaddleOcr_nativeLastConfidence(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    return gLastConf;
}

// Locks the bitmap and copies it into an RGB Mat (so the bitmap can be unlocked right away).
static bool bitmapToRgb(JNIEnv* env, jobject bitmap, cv::Mat& rgb) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width < 8 || info.height < 8 || info.width > 4096 || info.height > 4096)
        return false;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return false;
    try {
        cv::Mat rgba((int)info.height, (int)info.width, CV_8UC4, pixels, info.stride);
        cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);  // copies, so we can unlock now
    } catch (...) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return false;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

static jbyteArray toByteArray(JNIEnv* env, const std::string& text) {
    jbyteArray out = env->NewByteArray((jsize)text.size());
    if (out && !text.empty())
        env->SetByteArrayRegion(out, 0, (jsize)text.size(), reinterpret_cast<const jbyte*>(text.data()));
    return out;
}

// Returns UTF-8 bytes (not a jstring: the dictionary contains 4-byte emoji,
// which are invalid in JNI's modified UTF-8 and abort under CheckJNI).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_id_rexz_webtoonlite_NativePaddleOcr_nativeRecognize(JNIEnv* env, jclass, jobject bitmap) {
    std::lock_guard<std::mutex> lock(gMutex);
    gLastConf = 0.f;
    if (!gEngine || !bitmap) return nullptr;
    cv::Mat rgb;
    if (!bitmapToRgb(env, bitmap, rgb)) return nullptr;

    float conf = 0.f;
    std::string text = gEngine->recognize(rgb, &conf);
    gLastConf = conf;
    return toByteArray(env, text);
}

// Realtime mode: one record per line, "left\ttop\tright\tbottom\tconf_permille\ttext\n" (UTF-8).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_id_rexz_webtoonlite_NativePaddleOcr_nativeRecognizeLines(JNIEnv* env, jclass, jobject bitmap, jint maxSide) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gEngine || !bitmap) return nullptr;
    cv::Mat rgb;
    if (!bitmapToRgb(env, bitmap, rgb)) return nullptr;

    std::vector<OcrBox> boxes = gEngine->recognizeLines(rgb, (int)maxSide);
    std::string out;
    for (const auto& b : boxes) {
        std::string t = b.text;
        for (char& c : t) if (c == '\t' || c == '\n' || c == '\r') c = ' ';
        out += std::to_string(b.left) + '\t' + std::to_string(b.top) + '\t' + std::to_string(b.right) + '\t' +
               std::to_string(b.bottom) + '\t' + std::to_string((int)(b.conf * 1000.f)) + '\t' + t + '\n';
    }
    return toByteArray(env, out);
}
