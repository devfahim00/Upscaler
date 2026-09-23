# ----------------------------------------------------------------------------
# Upscaler JNI bridge to ncnn
#
# Links against the vendored ncnn prebuilt (ncnn-android/<ABI>/lib).
# The ncnn imported target (from its own ncnnConfig.cmake) automatically adds:
#   * include dirs
#   * -fno-rtti -fno-exceptions   (ncnn is compiled this way; our bridge code
#     must therefore avoid try/catch and dynamic_cast)
#   * OpenMP, Threads, glslang/SPIRV (for the Vulkan backend)
#   * android / jnigraphics / log
# ----------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22)
project(upscaler LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The vendored prebuilt ships one folder per ABI; ANDROID_ABI is set by AGP.
get_filename_component(NCNN_ANDROID_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../ncnn-android" ABSOLUTE)

if(NOT EXISTS "${NCNN_ANDROID_DIR}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
    message(FATAL_ERROR "ncnn prebuilt not found for ABI ${ANDROID_ABI}: "
            "${NCNN_ANDROID_DIR}/${ANDROID_ABI}")
endif()

set(ncnn_DIR "${NCNN_ANDROID_DIR}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(upscaler SHARED ncnn_jni.cpp)

target_link_libraries(upscaler
    ncnn
    android
    jnigraphics
    log)
