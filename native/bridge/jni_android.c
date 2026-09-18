//go:build android
// +build android

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "_cgo_export.h"

static JavaVM *portal_jvm;
static jclass portal_bridge_class;
static jmethodID portal_event_method;

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
    if (env == NULL || portal_bridge_class == NULL || portal_event_method == NULL) return;

    jstring tunnel = (*env)->NewStringUTF(env, tunnel_id != NULL ? tunnel_id : "");
    jstring type = (*env)->NewStringUTF(env, event_type != NULL ? event_type : "");
    jstring payload = (*env)->NewStringUTF(env, payload_json != NULL ? payload_json : "");
    if (tunnel != NULL && type != NULL && payload != NULL) {
        (*env)->CallStaticVoidMethod(env, portal_bridge_class, portal_event_method, tunnel, type, payload);
    }
    if (tunnel != NULL) (*env)->DeleteLocalRef(env, tunnel);
    if (type != NULL) (*env)->DeleteLocalRef(env, type);
    if (payload != NULL) (*env)->DeleteLocalRef(env, payload);
    if (attached) (*portal_jvm)->DetachCurrentThread(portal_jvm);
}

static void throw_portal_error(JNIEnv *env, char *error) {
    jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception != NULL) {
        (*env)->ThrowNew(env, exception, error != NULL ? error : "Portal native call failed");
    }
    if (error != NULL) PortalFreeString(error);
}

static char *utf8(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *input = (*env)->GetStringUTFChars(env, value, NULL);
    if (input == NULL) return NULL;
    char *copy = strdup(input);
    (*env)->ReleaseStringUTFChars(env, value, input);
    return copy;
}

static jstring result_string(JNIEnv *env, int code, char *result, char *error) {
    if (code != 0) {
        if (result != NULL) PortalFreeString(result);
        throw_portal_error(env, error);
        return NULL;
    }
    jstring value = (*env)->NewStringUTF(env, result != NULL ? result : "");
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
    int code = PortalGenerateIdentity(input, &out, &error); free(input);
    return result_string(env, code, out, error);
}

JNIEXPORT jstring JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeParseIdentity(JNIEnv *env, jobject self, jstring json) {
    (void)self; char *input = utf8(env, json); char *out = NULL; char *error = NULL;
    int code = PortalParseIdentity(input, &out, &error); free(input);
    return result_string(env, code, out, error);
}

JNIEXPORT jstring JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeStart(JNIEnv *env, jobject self, jstring json) {
    (void)self; char *input = utf8(env, json); char *out = NULL; char *error = NULL;
    int code = PortalStart(input, &out, &error); free(input);
    return result_string(env, code, out, error);
}

JNIEXPORT void JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeStop(JNIEnv *env, jobject self, jstring tunnel_id) {
    (void)self; char *id = utf8(env, tunnel_id); char *error = NULL;
    int code = PortalStop(id, &error); free(id);
    if (code != 0) throw_portal_error(env, error); else if (error != NULL) PortalFreeString(error);
}

JNIEXPORT void JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeStopAll(JNIEnv *env, jobject self) {
    (void)env; (void)self; PortalStopAll();
}

JNIEXPORT jstring JNICALL Java_org_gosuda_portal_android_internal_NativeBridge_nativeGetStatus(JNIEnv *env, jobject self, jstring tunnel_id) {
    (void)self; char *id = utf8(env, tunnel_id); char *out = NULL; char *error = NULL;
    int code = PortalGetStatus(id, &out, &error); free(id);
    return result_string(env, code, out, error);
}

static void relay_call(JNIEnv *env, jstring tunnel_id, jstring relay_url, int add) {
    char *id = utf8(env, tunnel_id); char *url = utf8(env, relay_url); char *error = NULL;
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
    (void)self; char *id = utf8(env, tunnel_id); char *metadata = utf8(env, metadata_json); char *error = NULL;
    int code = PortalUpdateMetadata(id, metadata, &error); free(id); free(metadata);
    if (code != 0) throw_portal_error(env, error); else if (error != NULL) PortalFreeString(error);
}
