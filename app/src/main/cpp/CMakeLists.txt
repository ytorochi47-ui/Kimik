cmake_minimum_required(VERSION 3.10)
project(webtoonocr LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# AGP's debug variant would otherwise compile native code with -O0, which makes
# OCR several times slower. Always optimize and strip: this module is a library
# for the OCR engine, not something we step through in a debugger.
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

set(ncnn_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn/${ANDROID_ABI}/lib/cmake/ncnn")
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv/sdk/native/jni")
find_package(ncnn REQUIRED)
find_package(OpenCV REQUIRED COMPONENTS core imgproc)

add_library(webtoonocr SHARED OcrBridge.cpp webtoon_ocr.cpp)

# ncnn's exported usage requirements add -fno-exceptions/-fno-rtti. OpenCV can
# throw cv::Exception, which we catch in webtoon_ocr.cpp, so these two files
# are compiled with exceptions enabled (source-level options come last).
set_source_files_properties(OcrBridge.cpp webtoon_ocr.cpp PROPERTIES COMPILE_OPTIONS "-fexceptions")

target_compile_options(webtoonocr PRIVATE -ffunction-sections -fdata-sections -fvisibility=hidden)
target_link_libraries(webtoonocr ncnn ${OpenCV_LIBS} jnigraphics log)
target_link_options(webtoonocr PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -s)
