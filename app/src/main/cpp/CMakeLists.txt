cmake_minimum_required(VERSION 3.22.1)
project(fpnative C)

# Static FFmpeg built from source by native/build-ffmpeg.sh (see docs/fork/DEPENDENCIES.md).
set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/out/${ANDROID_ABI}")
if(NOT EXISTS "${FFMPEG_DIR}/lib/libavcodec.a")
    message(FATAL_ERROR "FFmpeg for ${ANDROID_ABI} missing: run native/build-ffmpeg.sh ${ANDROID_ABI}")
endif()

add_library(fpnative SHARED fpnative.c)
target_include_directories(fpnative PRIVATE "${FFMPEG_DIR}/include")
target_compile_options(fpnative PRIVATE -O2 -Wall)
target_link_libraries(fpnative
    "${FFMPEG_DIR}/lib/libavformat.a"
    "${FFMPEG_DIR}/lib/libavcodec.a"
    "${FFMPEG_DIR}/lib/libswresample.a"
    "${FFMPEG_DIR}/lib/libavutil.a"
    log m z)
# 16 KB page size compatibility (Android 15+ devices with 16 KB pages).
target_link_options(fpnative PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL")
