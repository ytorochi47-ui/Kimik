// PP-OCRv5 Mobile (ncnn) engine for WebtoonLite.
// Detection/recognition logic is derived from nihui/ncnn-android-ppocrv5
// (BSD 3-Clause, Copyright (C) 2025 THL A29 Limited, a Tencent company).
#ifndef WEBTOON_OCR_H
#define WEBTOON_OCR_H

#include <string>
#include <vector>
#include <opencv2/core/core.hpp>
#include <net.h>

struct OcrLine {
    cv::RotatedRect rrect;
    int orientation = 0;
    float detScore = 0.f;
    std::string text;
    float conf = 0.f;     // mean softmax probability of the decoded characters
    int chars = 0;
};

// One recognized text line with an axis-aligned box in input-image pixels.
struct OcrBox {
    int left = 0, top = 0, right = 0, bottom = 0;
    float conf = 0.f;
    std::string text;
};

class WebtoonOcr {
public:
    WebtoonOcr();

    // Loads from Android assets (mgr != nullptr) or from plain files.
    int load(AAssetManager* mgr, const char* detParam, const char* detBin,
             const char* recParam, const char* recBin);

    // Runs detection + recognition on an RGB image. Never throws.
    // Returns text lines in reading order joined by '\n'; meanConf receives the
    // character-weighted mean confidence (0 when nothing was read).
    std::string recognize(const cv::Mat& rgb, float* meanConf);

    // Same detection/recognition, but returns every line with its box (used by realtime
    // mode, which groups lines into dialogs itself). maxSide caps the detector input.
    std::vector<OcrBox> recognizeLines(const cv::Mat& rgb, int maxSide);

private:
    int detect(const cv::Mat& rgb, std::vector<OcrLine>& out, int targetSize);
    int recognizeLine(const cv::Mat& rgb, OcrLine& line);
    // Detect + recognize; keeps lines that read confidently. Shared by recognize()/recognizeLines().
    bool readLines(const cv::Mat& rgb, int maxTarget, size_t maxLines, std::vector<OcrLine>& done);

    ncnn::Net det_;
    ncnn::Net rec_;
};

#endif
