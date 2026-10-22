// JNI wrapper around WebtoonOcr. Third-party pieces (dictionary header, models,
// ncnn, OpenCV Mobile) are fetched by scripts/prepare_native_ocr.py during CI.
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <mutex>
#include <string>
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

// Returns UTF-8 bytes (not a jstring: the dictionary contains 4-byte emoji,
// which are invalid in JNI's modified UTF-8 and abort under CheckJNI).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_id_rexz_webtoonlite_NativePaddleOcr_nativeRecognize(JNIEnv* env, jclass, jobject bitmap) {
    std::lock_guard<std::mutex> lock(gMutex);
    gLastConf = 0.f;
    if (!gEngine || !bitmap) return nullptr;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width < 8 || info.height < 8 || info.width > 4096 || info.height > 4096)
        return nullptr;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return nullptr;
    cv::Mat rgb;
    try {
        cv::Mat rgba((int)info.height, (int)info.width, CV_8UC4, pixels, info.stride);
        cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);  // copies, so we can unlock now
    } catch (...) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return nullptr;
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    float conf = 0.f;
    std::string text = gEngine->recognize(rgb, &conf);
    gLastConf = conf;
    jbyteArray out = env->NewByteArray((jsize)text.size());
    if (out && !text.empty())
        env->SetByteArrayRegion(out, 0, (jsize)text.size(), reinterpret_cast<const jbyte*>(text.data()));
    return out;
}
