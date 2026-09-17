# The prebuilt libportaltunnel.so resolves JNI functions by mangled name.
# Keep the exact class and method names through R8 shrinking.
-keep class org.gosuda.portal.android.internal.NativeBridge { *; }
