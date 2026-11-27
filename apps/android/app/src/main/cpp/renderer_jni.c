#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "renderer.h"

static void fail(JNIEnv *env, const char *type, const char *message)
{
    jclass exception = (*env)->FindClass(env, type);
    if (exception) (*env)->ThrowNew(env, exception, message);
}

/* Keep allocation/exception handling in one boundary. All C APIs accept ordinary
 * NUL-terminated UTF-8 and return malloc-owned UTF-8 JSON. Java byte arrays avoid
 * the modified UTF-8 encoding used by JNI's GetStringUTFChars(). */
static jbyteArray invoke(JNIEnv *env, jbyteArray request, char *(*operation)(const char *))
{
    if (!request) {
        fail(env, "java/lang/IllegalArgumentException", "Missing renderer request.");
        return NULL;
    }
    jsize length = (*env)->GetArrayLength(env, request);
    char *input = malloc((size_t)length + 1);
    if (!input) {
        fail(env, "java/lang/OutOfMemoryError", "Cannot allocate renderer request.");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, request, 0, length, (jbyte *)input);
    if ((*env)->ExceptionCheck(env)) {
        free(input);
        return NULL;
    }
    input[length] = '\0';
    if (memchr(input, '\0', (size_t)length)) {
        free(input);
        fail(env, "java/lang/IllegalArgumentException", "Invalid renderer request.");
        return NULL;
    }
    char *output = operation(input);
    free(input);
    if (!output) {
        fail(env, "java/lang/IllegalStateException", "Cannot render website.");
        return NULL;
    }
    size_t output_length = strlen(output);
    if (output_length > INT32_MAX) {
        webeasy_free(output);
        fail(env, "java/lang/OutOfMemoryError", "Rendered website is too large.");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)output_length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)output_length, (const jbyte *)output);
    webeasy_free(output);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_no_webeasy_app_NativeRenderer_renderBytes(JNIEnv *env, jobject instance, jbyteArray request)
{
    (void)instance;
    return invoke(env, request, webeasy_render);
}

JNIEXPORT jbyteArray JNICALL
Java_no_webeasy_app_NativeRenderer_generateBytes(JNIEnv *env, jobject instance, jbyteArray request, jboolean compile)
{
    (void)instance;
    return invoke(env, request, compile ? webeasy_compile : webeasy_normalize);
}
