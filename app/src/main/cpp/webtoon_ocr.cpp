// See webtoon_ocr.h for provenance (BSD 3-Clause, Tencent THL A29 Limited).
#include "webtoon_ocr.h"

#include <algorithm>
#include <cmath>
#include <exception>

#include "cpu.h"
#include <opencv2/imgproc/imgproc.hpp>

#include "ppocrv5_dict.h"

namespace {

double contourScore(const cv::Mat& prob, const std::vector<cv::Point>& contour) {
    cv::Rect rect = cv::boundingRect(contour);
    rect &= cv::Rect(0, 0, prob.cols, prob.rows);
    if (rect.width <= 0 || rect.height <= 0) return 0.0;
    cv::Mat roi = prob(rect);
    cv::Mat mask = cv::Mat::zeros(rect.height, rect.width, CV_8U);
    std::vector<cv::Point> shifted;
    shifted.reserve(contour.size());
    for (const auto& p : contour) shifted.emplace_back(p.x - rect.x, p.y - rect.y);
    std::vector<std::vector<cv::Point> > polys(1, shifted);
    cv::fillPoly(mask, polys, cv::Scalar(255));
    return cv::mean(roi, mask).val[0] / 255.0;
}

// Crop one detected text line to a 48-px-high strip for the recognizer.
bool cropLine(const cv::Mat& rgb, const OcrLine& line, cv::Mat& dst) {
    const float rw = line.rrect.size.width;
    const float rh = line.rrect.size.height;
    if (!(rw > 1.f) || !(rh > 1.f)) return false;
    const int th = 48;
    float tw = rh * th / rw;
    if (!(tw >= 8.f)) tw = 8.f;
    if (tw > 2400.f) tw = 2400.f;  // guard against degenerate boxes
    cv::Point2f c[4];
    line.rrect.points(c);
    std::vector<cv::Point2f> s(3), d(3);
    if (line.orientation == 0) { s[0] = c[0]; s[1] = c[1]; s[2] = c[3]; }
    else { s[0] = c[2]; s[1] = c[3]; s[2] = c[1]; }
    d[0] = cv::Point2f(0, 0);
    d[1] = cv::Point2f(tw, 0);
    d[2] = cv::Point2f(0, (float)th);
    cv::Mat tm = cv::getAffineTransform(s, d);
    cv::warpAffine(rgb, dst, tm, cv::Size((int)tw, th), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
    return !dst.empty();
}

}  // namespace

WebtoonOcr::WebtoonOcr() {}

int WebtoonOcr::load(AAssetManager* mgr, const char* detParam, const char* detBin,
                     const char* recParam, const char* recBin) {
    // FP32 on CPU: the Snapdragon 460 class cores have no FP16 arithmetic and
    // Vulkan on low-end GPUs is slower and less stable than the CPU path.
    det_.clear();
    rec_.clear();
    det_.opt.use_vulkan_compute = false;
    rec_.opt.use_vulkan_compute = false;
    det_.opt.use_fp16_packed = det_.opt.use_fp16_storage = det_.opt.use_fp16_arithmetic = false;
    rec_.opt.use_fp16_packed = rec_.opt.use_fp16_storage = rec_.opt.use_fp16_arithmetic = false;
    int r = 0;
    if (mgr) {
        r |= det_.load_param(mgr, detParam);
        r |= det_.load_model(mgr, detBin);
        r |= rec_.load_param(mgr, recParam);
        r |= rec_.load_model(mgr, recBin);
    } else {
        r |= det_.load_param(detParam);
        r |= det_.load_model(detBin);
        r |= rec_.load_param(recParam);
        r |= rec_.load_model(recBin);
    }
    return r;
}

int WebtoonOcr::detect(const cv::Mat& rgb, std::vector<OcrLine>& out, int targetSize) {
    const int imgW = rgb.cols, imgH = rgb.rows;
    const int stride = 32;

    // Letterbox so the longer side equals targetSize (up- or down-scaling).
    float scale = (float)targetSize / (float)std::max(imgW, imgH);
    int w = std::max(1, (int)std::lround(imgW * scale));
    int h = std::max(1, (int)std::lround(imgH * scale));

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgb.data, ncnn::Mat::PIXEL_RGB2BGR, imgW, imgH, w, h);
    int wpad = (w + stride - 1) / stride * stride - w;
    int hpad = (h + stride - 1) / stride * stride - h;
    ncnn::Mat padded;
    ncnn::copy_make_border(in, padded, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2,
                           ncnn::BORDER_CONSTANT, 114.f);
    const float mean[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
    const float norm[3] = {1 / 0.229f / 255.f, 1 / 0.224f / 255.f, 1 / 0.225f / 255.f};
    padded.substract_mean_normalize(mean, norm);

    ncnn::Extractor ex = det_.create_extractor();
    ex.input("in0", padded);
    ncnn::Mat prob;
    if (ex.extract("out0", prob) != 0) return -1;
    const float denorm[1] = {255.f};
    prob.substract_mean_normalize(0, denorm);

    cv::Mat pred(prob.h, prob.w, CV_8UC1);
    prob.to_pixels(pred.data, ncnn::Mat::PIXEL_GRAY);
    cv::Mat bitmap;
    cv::threshold(pred, bitmap, 0.3f * 255, 255, cv::THRESH_BINARY);

    const float boxThresh = 0.6f;
    const float enlarge = 1.95f;
    const float minSize = 3 * scale;
    std::vector<std::vector<cv::Point> > contours;
    cv::findContours(bitmap, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
    if (contours.size() > 1000) contours.resize(1000);

    for (const auto& contour : contours) {
        if (contour.size() <= 2) continue;
        double score = contourScore(pred, contour);
        if (score < boxThresh) continue;
        cv::RotatedRect rr = cv::minAreaRect(contour);
        if (std::max(rr.size.width, rr.size.height) < minSize) continue;

        int orientation = 0;
        if (rr.angle >= -30 && rr.angle <= 30 && rr.size.height > rr.size.width * 2.7) orientation = 1;
        if ((rr.angle <= -60 || rr.angle >= 60) && rr.size.width > rr.size.height * 2.7) orientation = 1;
        if (rr.angle < -30) rr.angle += 180;
        if (orientation == 0 && rr.angle < 30) { rr.angle += 90; std::swap(rr.size.width, rr.size.height); }
        if (orientation == 1 && rr.angle >= 60) { rr.angle -= 90; std::swap(rr.size.width, rr.size.height); }

        rr.size.height += rr.size.width * (enlarge - 1);
        rr.size.width *= enlarge;
        rr.center.x = (rr.center.x - (wpad / 2)) / scale;
        rr.center.y = (rr.center.y - (hpad / 2)) / scale;
        rr.size.width /= scale;
        rr.size.height /= scale;

        OcrLine line;
        line.rrect = rr;
        line.orientation = orientation;
        line.detScore = (float)score;
        out.push_back(line);
    }
    return 0;
}

int WebtoonOcr::recognizeLine(const cv::Mat& rgb, OcrLine& line) {
    cv::Mat roi;
    if (!cropLine(rgb, line, roi)) return -1;
    ncnn::Mat in = ncnn::Mat::from_pixels(roi.data, ncnn::Mat::PIXEL_RGB2BGR, roi.cols, roi.rows);
    const float mean[3] = {127.5f, 127.5f, 127.5f};
    const float norm[3] = {1.f / 127.5f, 1.f / 127.5f, 1.f / 127.5f};
    in.substract_mean_normalize(mean, norm);

    ncnn::Extractor ex = rec_.create_extractor();
    ex.input("in0", in);
    ncnn::Mat out;
    if (ex.extract("out0", out) != 0) return -1;

    // CTC greedy decode. Class 0 = blank, class k = dictionary entry k-1,
    // class (dictSize + 1) = the space character that is not in the table.
    int last = 0;
    double sum = 0;
    for (int i = 0; i < out.h; i++) {
        const float* p = out.row(i);
        int idx = 0;
        float best = -9999.f;
        for (int j = 0; j < out.w; j++) {
            if (p[j] > best) { best = p[j]; idx = j; }
        }
        if (idx == last) continue;
        last = idx;
        if (idx <= 0) continue;
        const int id = idx - 1;
        if (id < character_dict_size) {
            line.text += character_dict[id];
            sum += best;
            line.chars++;
        } else if (!line.text.empty() && line.text.back() != ' ') {
            line.text.push_back(' ');
        }
    }
    while (!line.text.empty() && line.text.back() == ' ') line.text.pop_back();
    line.conf = line.chars > 0 ? (float)(sum / line.chars) : 0.f;
    return 0;
}

bool WebtoonOcr::readLines(const cv::Mat& rgb, int maxTarget, size_t maxLines, std::vector<OcrLine>& done) {
    if (rgb.empty() || rgb.type() != CV_8UC3) return false;

    // Text in a user-selected balloon: keep native resolution up to maxTarget px,
    // lift very small crops to 480 px so thin comic strokes survive the
    // detector's /32 downsampling, and never exceed maxTarget on a 4 GB phone.
    const int longSide = std::max(rgb.cols, rgb.rows);
    int target = longSide;
    if (target < 480) target = 480;
    if (target > maxTarget) target = maxTarget;

    std::vector<OcrLine> lines;
    if (detect(rgb, lines, target) != 0 || lines.empty()) return false;

    // Recognize the largest lines first and cap the work per request.
    std::sort(lines.begin(), lines.end(), [](const OcrLine& a, const OcrLine& b) {
        return a.rrect.size.area() > b.rrect.size.area();
    });
    if (lines.size() > maxLines) lines.resize(maxLines);

    for (auto& l : lines) {
        try {
            if (recognizeLine(rgb, l) == 0 && !l.text.empty() && l.conf >= 0.30f) done.push_back(l);
        } catch (const std::exception&) {
            // one bad box must not lose the whole balloon
        }
    }
    return !done.empty();
}

std::vector<OcrBox> WebtoonOcr::recognizeLines(const cv::Mat& rgb, int maxSide) {
    std::vector<OcrBox> result;
    try {
        std::vector<OcrLine> done;
        if (!readLines(rgb, std::max(480, std::min(maxSide, 1600)), 64, done)) return result;
        for (const auto& l : done) {
            cv::Point2f c[4];
            l.rrect.points(c);
            float x0 = c[0].x, x1 = c[0].x, y0 = c[0].y, y1 = c[0].y;
            for (int i = 1; i < 4; i++) {
                x0 = std::min(x0, c[i].x); x1 = std::max(x1, c[i].x);
                y0 = std::min(y0, c[i].y); y1 = std::max(y1, c[i].y);
            }
            OcrBox b;
            b.left = std::max(0, (int)std::floor(x0));
            b.top = std::max(0, (int)std::floor(y0));
            b.right = std::min(rgb.cols, (int)std::ceil(x1));
            b.bottom = std::min(rgb.rows, (int)std::ceil(y1));
            if (b.right - b.left < 2 || b.bottom - b.top < 2) continue;
            b.conf = l.conf;
            b.text = l.text;
            result.push_back(b);
        }
    } catch (...) {
        result.clear();
    }
    return result;
}

std::string WebtoonOcr::recognize(const cv::Mat& rgb, float* meanConf) {
    if (meanConf) *meanConf = 0.f;
    try {
        std::vector<OcrLine> done;
        if (!readLines(rgb, 960, 24, done)) return std::string();

        // Reading order: top-to-bottom. rrect short side is the (enlarged) text height,
        // so ~0.35 of it is about two thirds of a real line height.
        std::vector<float> heights;
        for (const auto& l : done) heights.push_back(std::min(l.rrect.size.width, l.rrect.size.height));
        std::nth_element(heights.begin(), heights.begin() + heights.size() / 2, heights.end());
        const float rowTol = std::max(8.f, heights[heights.size() / 2] * 0.35f);
        // Sort by Y, then split into rows and order each row by X. (A tolerance
        // comparator inside std::sort is not a strict weak ordering.)
        std::sort(done.begin(), done.end(), [](const OcrLine& a, const OcrLine& b) {
            return a.rrect.center.y < b.rrect.center.y;
        });
        std::vector<size_t> rowBreak;  // indices where a new text row starts
        size_t rowStart = 0;
        for (size_t i = 1; i <= done.size(); i++) {
            if (i == done.size() ||
                done[i].rrect.center.y - done[rowStart].rrect.center.y > rowTol) {
                std::sort(done.begin() + rowStart, done.begin() + i,
                          [](const OcrLine& a, const OcrLine& b) {
                              return a.rrect.center.x < b.rrect.center.x;
                          });
                rowBreak.push_back(rowStart);
                rowStart = i;
            }
        }

        // Boxes on the same row (e.g. words of stylised or white-on-dark text that the
        // detector split) are joined with spaces; separate rows with newlines.
        std::string result;
        double confSum = 0;
        int chars = 0;
        size_t nextBreak = 0;
        for (size_t i = 0; i < done.size(); i++) {
            const bool newRow = nextBreak < rowBreak.size() && rowBreak[nextBreak] == i;
            if (newRow) nextBreak++;
            if (!result.empty()) result.push_back(newRow ? '\n' : ' ');
            result += done[i].text;
            confSum += (double)done[i].conf * done[i].chars;
            chars += done[i].chars;
        }
        if (meanConf && chars > 0) *meanConf = (float)(confSum / chars);
        return result;
    } catch (const std::exception&) {
        return std::string();
    } catch (...) {
        return std::string();
    }
}
