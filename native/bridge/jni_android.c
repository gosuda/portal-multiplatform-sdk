//go:build android
// +build android

#include <jni.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include "_cgo_export.h"

static JavaVM *portal_jvm;
static jclass portal_bridge_class;
static jmethodID portal_event_method;

static void throw_java_error(JNIEnv *env, const char *type, const char *message) {
    jclass exception = (*env)->FindClass(env, type);
    if (exception != NULL) {
        (*env)->ThrowNew(env, exception, message);
        (*env)->DeleteLocalRef(env, exception);
    }
}

// The C ABI uses standard UTF-8, not JNI's modified UTF-8. In particular,
// supplementary code points must cross as four bytes, not surrogate pairs.
static jstring from_utf8(JNIEnv *env, const char *value) {
    if (value == NULL || *value == '\0') return (*env)->NewStringUTF(env, "");
    size_t length = 0;
    int ascii = 1;
    while (value[length] != '\0') {
        if ((unsigned char)value[length] >= 0x80) ascii = 0;
        length++;
    }
    if (ascii) return (*env)->NewStringUTF(env, value);
    if (length > INT_MAX) {
        throw_java_error(env, "java/lang/OutOfMemoryError", "Portal string is too large");
        return NULL;
    }
    jchar *chars = malloc(length * sizeof(jchar));
    if (chars == NULL) {
        throw_java_error(env, "java/lang/OutOfMemoryError", "Portal string allocation failed");
        return NULL;
    }
    const unsigned char *p = (const unsigned char *)value;
    const unsigned char *end = p + length;
    jsize count = 0;
    while (p < end) {
        unsigned int cp = *p++;
        if (cp >= 0xc2 && cp <= 0xdf && end - p >= 1 && (p[0] & 0xc0) == 0x80) {
            cp = ((cp & 0x1f) << 6) | (p[0] & 0x3f);
            p++;
        } else if (cp >= 0xe0 && cp <= 0xef && end - p >= 2 &&
                   (p[0] & 0xc0) == 0x80 && (p[1] & 0xc0) == 0x80 &&
                   (cp != 0xe0 || p[0] >= 0xa0) && (cp != 0xed || p[0] < 0xa0)) {
            cp = ((cp & 0x0f) << 12) | ((p[0] & 0x3f) << 6) | (p[1] & 0x3f);
            p += 2;
        } else if (cp >= 0xf0 && cp <= 0xf4 && end - p >= 3 &&
                   (p[0] & 0xc0) == 0x80 && (p[1] & 0xc0) == 0x80 && (p[2] & 0xc0) == 0x80 &&
                   (cp != 0xf0 || p[0] >= 0x90) && (cp != 0xf4 || p[0] < 0x90)) {
            cp = ((cp & 0x07) << 18) | ((p[0] & 0x3f) << 12) | ((p[1] & 0x3f) << 6) | (p[2] & 0x3f);
            p += 3;
        } else if (cp >= 0x80) {
            cp = 0xfffd;
        }
        if (cp > 0xffff) {
            cp -= 0x10000;
            chars[count++] = (jchar)(0xd800 | (cp >> 10));
            chars[count++] = (jchar)(0xdc00 | (cp & 0x3ff));
        } else {
            chars[count++] = (jchar)cp;
        }
    }
    jstring result = (*env)->NewString(env, chars, count);
    free(chars);
    return result;
}

static JNIEnv *portal_env(int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if (portal_jvm == NULL) return NULL;
    jint result = (*portal_jvm)->GetEnv(portal_jvm, (void **)&env, JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        if ((*portal_jvm)->AttachCurrentThread(portal_jvm, &env, NULL) != JNI_OK) return NULL;
        *attached = 1;
    } else if (result != JNI_OK) {
        return NULL;
    }
    return env;
}

static void portal_event_callback(const char *tunnel_id, const char *event_type, const char *payload_json) {
    int attached = 0;
    JNIEnv *env = portal_env(&attached);
    if (env == NULL) return;
    if (portal_bridge_class == NULL || portal_event_method == NULL) {
        if (attached) (*portal_jvm)->DetachCurrentThread(portal_jvm);
        return;
    }

    jstring tunnel = from_utf8(env, tunnel_id);
    jstring type = tunnel != NULL ? from_utf8(env, event_type) : NULL;
    jstring payload = type != NULL ? from_utf8(env, payload_json) : NULL;
    if (tunnel != NULL && type != NULL && payload != NULL) {
        (*env)->CallStaticVoidMethod(env, portal_bridge_class, portal_event_method, tunnel, type, payload);
    }
    if (tunnel != NULL) (*env)->DeleteLocalRef(env, tunnel);
    if (type != NULL) (*env)->DeleteLocalRef(env, type);
    if (payload != NULL) (*env)->DeleteLocalRef(env, payload);
    // Callback exceptions must never escape into Go or poison its next JNI call.
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*portal_jvm)->DetachCurrentThread(portal_jvm);
}

static void throw_portal_error(JNIEnv *env, char *error) {
    jstring message = from_utf8(env, error != NULL ? error : "Portal native call failed");
    if (message != NULL) {
        jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (exception != NULL) {
            jmethodID constructor = (*env)->GetMethodID(env, exception, "<init>", "(Ljava/lang/String;)V");
            if (constructor != NULL) {
                jthrowable failure = (*env)->NewObject(env, exception, constructor, message);
                if (failure != NULL) {
                    (*env)->Throw(env, failure);
                    (*env)->DeleteLocalRef(env, failure);
                }
            }
            (*env)->DeleteLocalRef(env, exception);
        }
        (*env)->DeleteLocalRef(env, message);
    }
    if (error != NULL) PortalFreeString(error);
}

static char *utf8(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    jsize length = (*env)->GetStringLength(env, value);
    const jchar *input = (*env)->GetStringChars(env, value, NULL);
    if (input == NULL) return NULL;
    char *copy = malloc((size_t)length * 3 + 1);
    if (copy == NULL) {
        (*env)->ReleaseStringChars(env, value, input);
        throw_java_error(env, "java/lang/OutOfMemoryError", "Portal string allocation failed");
        return NULL;
    }
    char *out = copy;
    for (jsize i = 0; i < length; i++) {
        unsigned int cp = input[i];
        if (cp == 0) {
            free(copy);
            (*env)->ReleaseStringChars(env, value, input);
            throw_java_error(env, "java/lang/IllegalArgumentException", "Portal native strings must not contain NUL");
            return NULL;
        }
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < length &&
            input[i + 1] >= 0xdc00 && input[i + 1] <= 0xdfff) {
            cp = 0x10000 + ((cp - 0xd800) << 10) + (input[++i] - 0xdc00);
        } else if (cp >= 0xd800 && cp <= 0xdfff) {
            cp = 0xfffd;
        }
        if (cp < 0x80) {
            *out++ = (char)cp;
        } else if (cp < 0x800) {
            *out++ = (char)(0xc0 | (cp >> 6));
            *out++ = (char)(0x80 | (cp & 0x3f));
        } else if (cp < 0x10000) {
            *out++ = (char)(0xe0 | (cp >> 12));
            *out++ = (char)(0x80 | ((cp >> 6) & 0x3f));
            *out++ = (char)(0x80 | (cp & 0x3f));
        } else {
            *out++ = (char)(0xf0 | (cp >> 18));
            *out++ = (char)(0x80 | ((cp >> 12) & 0x3f));
            *out++ = (char)(0x80 | ((cp >> 6) & 0x3f));
            *out++ = (char)(0x80 | (cp & 0x3f));
        }
    }
    *out = '\0';
    (*env)->ReleaseStringChars(env, value, input);
    return copy;
}

static jstring result_string(JNIEnv *env, int code, char *result, char *error) {
    if (code != 0) {
        if (result != NULL) PortalFreeString(result);
        throw_portal_error(env, error);
        return NULL;
    }
    jstring value = from_utf8(env, result);
    if (result != NULL) PortalFreeString(result);
    if (error != NULL) PortalFreeString(error);
    return value;
}

JNIEXPORT void JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_setupNativeCallback(JNIEnv *env, jobject self) {
    if ((*env)->GetJavaVM(env, &portal_jvm) != JNI_OK) return;
    jclass local = (*env)->GetObjectClass(env, self);
    if (portal_bridge_class != NULL) (*env)->DeleteGlobalRef(env, portal_bridge_class);
    portal_bridge_class = (*env)->NewGlobalRef(env, local);
    portal_event_method = (*env)->GetStaticMethodID(env, portal_bridge_class, "onNativeEvent", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    PortalSetEventCallback(portal_event_callback);
}

JNIEXPORT jstring JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeGenerateIdentity(JNIEnv *env, jobject self, jstring name) {
    (void)self; char *input = utf8(env, name); char *out = NULL; char *error = NULL;
    if ((*env)->ExceptionCheck(env)) { free(input); return NULL; }
    int code = PortalGenerateIdentity(input, &out, &error); free(input);
    return result_string(env, code, out, error);
}

JNIEXPORT jstring JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeParseIdentity(JNIEnv *env, jobject self, jstring json) {
    (void)self; char *input = utf8(env, json); char *out = NULL; char *error = NULL;
    if ((*env)->ExceptionCheck(env)) { free(input); return NULL; }
    int code = PortalParseIdentity(input, &out, &error); free(input);
    return result_string(env, code, out, error);
}

JNIEXPORT jstring JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeStart(JNIEnv *env, jobject self, jstring json) {
    (void)self; char *input = utf8(env, json); char *out = NULL; char *error = NULL;
    if ((*env)->ExceptionCheck(env)) { free(input); return NULL; }
    int code = PortalStart(input, &out, &error); free(input);
    return result_string(env, code, out, error);
}

JNIEXPORT void JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeStop(JNIEnv *env, jobject self, jstring tunnel_id) {
    (void)self; char *id = utf8(env, tunnel_id); char *error = NULL;
    if ((*env)->ExceptionCheck(env)) { free(id); return; }
    int code = PortalStop(id, &error); free(id);
    if (code != 0) throw_portal_error(env, error); else if (error != NULL) PortalFreeString(error);
}

JNIEXPORT void JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeStopAll(JNIEnv *env, jobject self) {
    (void)env; (void)self; PortalStopAll();
}

JNIEXPORT jstring JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeGetStatus(JNIEnv *env, jobject self, jstring tunnel_id) {
    (void)self; char *id = utf8(env, tunnel_id); char *out = NULL; char *error = NULL;
    if ((*env)->ExceptionCheck(env)) { free(id); return NULL; }
    int code = PortalGetStatus(id, &out, &error); free(id);
    return result_string(env, code, out, error);
}

static void relay_call(JNIEnv *env, jstring tunnel_id, jstring relay_url, int add) {
    char *id = utf8(env, tunnel_id);
    if ((*env)->ExceptionCheck(env)) { free(id); return; }
    char *url = utf8(env, relay_url); char *error = NULL;
    if ((*env)->ExceptionCheck(env)) { free(id); free(url); return; }
    int code = add ? PortalAddRelay(id, url, &error) : PortalRemoveRelay(id, url, &error);
    free(id); free(url);
    if (code != 0) throw_portal_error(env, error); else if (error != NULL) PortalFreeString(error);
}

JNIEXPORT void JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeAddRelay(JNIEnv *env, jobject self, jstring tunnel_id, jstring relay_url) {
    (void)self; relay_call(env, tunnel_id, relay_url, 1);
}

JNIEXPORT void JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeRemoveRelay(JNIEnv *env, jobject self, jstring tunnel_id, jstring relay_url) {
    (void)self; relay_call(env, tunnel_id, relay_url, 0);
}

JNIEXPORT void JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeUpdateMetadata(JNIEnv *env, jobject self, jstring tunnel_id, jstring metadata_json) {
    (void)self; char *id = utf8(env, tunnel_id);
    if ((*env)->ExceptionCheck(env)) { free(id); return; }
    char *metadata = utf8(env, metadata_json); char *error = NULL;
    if ((*env)->ExceptionCheck(env)) { free(id); free(metadata); return; }
    int code = PortalUpdateMetadata(id, metadata, &error); free(id); free(metadata);
    if (code != 0) throw_portal_error(env, error); else if (error != NULL) PortalFreeString(error);
}
