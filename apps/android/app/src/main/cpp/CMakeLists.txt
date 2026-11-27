cmake_minimum_required(VERSION 3.22.1)
project(webeasy_renderer C)

get_filename_component(RENDERER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../shared/renderer" ABSOLUTE)
file(READ "${RENDERER_ROOT}/html-dependencies.json" HTML_DEPENDENCIES)
string(JSON DIRECTORY_COUNT LENGTH "${HTML_DEPENDENCIES}" recursiveSourceDirectories)
math(EXPR LAST_DIRECTORY "${DIRECTORY_COUNT} - 1")
foreach(INDEX RANGE ${LAST_DIRECTORY})
    string(JSON DIRECTORY GET "${HTML_DEPENDENCIES}" recursiveSourceDirectories ${INDEX})
    file(GLOB_RECURSE DIRECTORY_SOURCES CONFIGURE_DEPENDS "${RENDERER_ROOT}/${DIRECTORY}/*.c")
    list(APPEND LEXBOR_SOURCES ${DIRECTORY_SOURCES})
endforeach()
add_library(webeasy_renderer SHARED
    renderer_jni.c
    "${RENDERER_ROOT}/renderer.c"
    "${RENDERER_ROOT}/site.c"
    "${RENDERER_ROOT}/html.c"
    "${RENDERER_ROOT}/vendor/mustach/mustach.c"
    "${RENDERER_ROOT}/vendor/cjson/cJSON.c"
    "${RENDERER_ROOT}/vendor/utf8proc/utf8proc.c"
    "${RENDERER_ROOT}/vendor/lexbor/source/lexbor/ports/posix/lexbor/core/memory.c"
    ${LEXBOR_SOURCES}
)
set_target_properties(webeasy_renderer PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES)
target_compile_definitions(webeasy_renderer PRIVATE NO_OPEN_MEMSTREAM LEXBOR_STATIC UTF8PROC_STATIC)
target_include_directories(webeasy_renderer PRIVATE
    "${RENDERER_ROOT}"
    "${RENDERER_ROOT}/vendor/mustach"
    "${RENDERER_ROOT}/vendor/cjson"
    "${RENDERER_ROOT}/vendor/utf8proc"
    "${RENDERER_ROOT}/vendor/lexbor/source"
)
