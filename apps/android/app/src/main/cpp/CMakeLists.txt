cmake_minimum_required(VERSION 3.22.1)
project(webeasy_renderer C)

get_filename_component(RENDERER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../shared/renderer" ABSOLUTE)
add_library(webeasy_renderer SHARED
    renderer_jni.c
    "${RENDERER_ROOT}/renderer.c"
    "${RENDERER_ROOT}/vendor/mustach/mustach.c"
    "${RENDERER_ROOT}/vendor/cjson/cJSON.c"
)
set_target_properties(webeasy_renderer PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES)
target_compile_definitions(webeasy_renderer PRIVATE NO_OPEN_MEMSTREAM)
target_include_directories(webeasy_renderer PRIVATE
    "${RENDERER_ROOT}"
    "${RENDERER_ROOT}/vendor/mustach"
    "${RENDERER_ROOT}/vendor/cjson"
)
