#include <jni.h>
#include <jawt_md.h>
#include <cstdio>
#include <d3d11.h>
#include <wrl/client.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <WebView2.h>
#include <string>
#include <stdexcept>
#include "webview_host.h"

extern "C" JNIEXPORT jstring JNICALL
Java_li_songe_compose_webview2_internal_NativeBridge_diagnostics(JNIEnv* env, jobject) {
    std::string result = "JNI OK; ";
    Microsoft::WRL::ComPtr<ID3D11Device> device;
    const HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION,
        device.GetAddressOf(), nullptr, nullptr);
    result += SUCCEEDED(hr) ? "D3D11 hardware OK; " : "D3D11 hardware unavailable; ";
    // Balance initialization only when this call owns a successful COM initialization.
    const HRESULT apartment = RoInitialize(RO_INIT_MULTITHREADED);
    try {
        result += winrt::Windows::Graphics::Capture::GraphicsCaptureSession::IsSupported()
            ? "Graphics Capture supported" : "Graphics Capture unsupported";
    } catch (const winrt::hresult_error&) {
        result += "Graphics Capture probe failed";
    }
    if (SUCCEEDED(apartment)) RoUninitialize();
    return env->NewStringUTF(result.c_str());
}

namespace {
std::wstring wide(JNIEnv* env, jstring value) {
    if (!value) return {};
    const auto chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return result;
}
jstring string(JNIEnv* env, const std::wstring& value) {
    return env->NewString(reinterpret_cast<const jchar*>(value.data()), static_cast<jsize>(value.size()));
}
void error(JNIEnv* env) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Native browser operation failed"); }
}
#define JNI_METHOD(name) Java_li_songe_compose_webview2_internal_NativeBridge_##name
// Loader lookup only: no COM environment, controller, window or browser process.
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(availableBrowserVersion)(JNIEnv* env, jobject) {
    LPWSTR version = nullptr;
    const HRESULT hr = GetAvailableCoreWebView2BrowserVersionString(nullptr, &version);
    if (FAILED(hr)) {
        CoTaskMemFree(version);
        if (hr == HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND)) return nullptr;
        char message[96];
        std::snprintf(message, sizeof(message), "WebView2 runtime query failed (HRESULT 0x%08lX)", static_cast<unsigned long>(hr));
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
        return nullptr;
    }
    if (!version || !*version) {
        CoTaskMemFree(version);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "WebView2 loader returned an empty version");
        return nullptr;
    }
    auto result = env->NewString(reinterpret_cast<const jchar*>(version), static_cast<jsize>(wcslen(version)));
    CoTaskMemFree(version);
    return result;
}
// Called on the AWT event thread while the Window peer is alive. We only read
// its HWND; no drawing or focus changes are performed through JAWT.
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(windowHandle)(JNIEnv* env, jobject, jobject window) {
    JAWT awt{};
    awt.version = JAWT_VERSION_9;
    HWND hwnd{};
    if (JAWT_GetAWT(env, &awt)) {
        auto surface = awt.GetDrawingSurface(env, window);
        if (surface) {
            const auto flags = surface->Lock(surface);
            if (!(flags & JAWT_LOCK_ERROR)) {
                auto info = surface->GetDrawingSurfaceInfo(surface);
                if (info) {
                    auto platform = static_cast<JAWT_Win32DrawingSurfaceInfo*>(info->platformInfo);
                    if (platform) hwnd = platform->hwnd;
                    surface->FreeDrawingSurfaceInfo(info);
                }
                surface->Unlock(surface);
            }
            awt.FreeDrawingSurface(surface);
        }
    }
    if (!hwnd && !env->ExceptionCheck())
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Cannot obtain HWND from displayable AWT window");
    return reinterpret_cast<jlong>(hwnd);
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(activeHosts)(JNIEnv*, jobject) { return browser::activeHosts(); }
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(takeFocusMove)(JNIEnv*, jobject, jlong id) { return browser::takeFocusMove(id); }
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv* env, jobject, jlong parent, jstring profile, jint colorScheme) {
    try { return browser::create(parent, wide(env, profile), {}, L"", colorScheme); } catch (...) { error(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv* env, jobject, jlong id) {
    try { browser::close(id); } catch (...) { error(env); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(resize)(JNIEnv* env, jobject, jlong id, jint x, jint y, jint w, jint h, jdouble scale, jlong revision) {
    try { browser::resize(id, x, y, w, h, scale, revision); } catch (...) { error(env); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(navigate)(JNIEnv* env, jobject, jlong id, jstring value, jboolean html) {
    try { browser::navigate(id, wide(env, value), html); } catch (...) { error(env); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(mouse)(JNIEnv* env, jobject, jlong id, jint message, jint keys, jint data, jint x, jint y) {
    try { browser::mouse(id, message, keys, data, x, y); } catch (...) { error(env); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(focus)(JNIEnv* env, jobject, jlong id, jboolean enabled) {
    try { browser::focus(id, enabled); } catch (...) { error(env); }
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(status)(JNIEnv* env, jobject, jlong id) {
    try { return string(env, browser::status(id)); } catch (...) { error(env); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(frame)(JNIEnv* env, jobject, jlong id, jlong after) {
    try {
        auto bytes = browser::frame(id, after);
        if (bytes.empty()) return nullptr;
        auto array = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
        return array;
    } catch (...) { error(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(script)(JNIEnv* env, jobject, jlong id, jstring value) {
    try { browser::script(id, wide(env, value)); } catch (...) { error(env); }
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(scriptResult)(JNIEnv* env, jobject, jlong id) {
    try { return string(env, browser::scriptResult(id)); } catch (...) { error(env); return nullptr; }
}

namespace {
struct AttachedEnv {
    JavaVM* vm;
    JNIEnv* env{};
    bool attached{};
    explicit AttachedEnv(JavaVM* value) : vm(value) {
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_EDETACHED) {
            if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
                throw std::runtime_error("Cannot attach bridge thread");
            attached = true;
        }
    }
    ~AttachedEnv() { if (attached) vm->DetachCurrentThread(); }
};
// Application callback names are protocol keys; only JDK interface methods are looked up.
struct LocalFrame {
    JNIEnv* env;
    explicit LocalFrame(JNIEnv* value) : env(value) {
        if (env->PushLocalFrame(16) < 0) throw std::bad_alloc();
    }
    ~LocalFrame() { env->PopLocalFrame(nullptr); }
};
void checkJava(JNIEnv* env) {
    if (env->ExceptionCheck()) throw std::runtime_error("Java bridge operation failed");
}
struct JavaBridge final : browser::BridgeCallbacks {
    JavaVM* vm{};
    jobject invocation{}, navigation{}, shutdown{};
    jmethodID invokeMethod{}, navigationMethod{}, closeMethod{};
    JavaBridge(JNIEnv* env, jobject callbacks) {
        env->GetJavaVM(&vm);
        LocalFrame frame(env);
        try {
            auto mapType = env->FindClass("java/util/Map");
            checkJava(env);
            if (!callbacks || !env->IsInstanceOf(callbacks, mapType))
                throw std::runtime_error("Expected callback map");
            auto get = env->GetMethodID(mapType, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
            checkJava(env);
            auto bind = [&](const char* key, const char* interfaceName, const char* method,
                            const char* signature, jobject& target, jmethodID& methodId) {
                auto type = env->FindClass(interfaceName);
                checkJava(env);
                auto name = env->NewStringUTF(key);
                checkJava(env);
                auto callback = env->CallObjectMethod(callbacks, get, name);
                checkJava(env);
                if (!callback || !env->IsInstanceOf(callback, type))
                    throw std::runtime_error("Missing or invalid callback");
                methodId = env->GetMethodID(type, method, signature);
                checkJava(env);
                target = env->NewGlobalRef(callback);
                checkJava(env);
                if (!target) throw std::bad_alloc();
            };
            bind("invoke", "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", invocation, invokeMethod);
            bind("navigated", "java/util/function/LongConsumer", "accept", "(J)V", navigation, navigationMethod);
            bind("close", "java/lang/Runnable", "run", "()V", shutdown, closeMethod);
        } catch (...) {
            env->DeleteGlobalRef(invocation);
            env->DeleteGlobalRef(navigation);
            env->DeleteGlobalRef(shutdown);
            throw;
        }
    }
    ~JavaBridge() override {
        try {
            AttachedEnv scope(vm);
            scope.env->DeleteGlobalRef(invocation);
            scope.env->DeleteGlobalRef(navigation);
            scope.env->DeleteGlobalRef(shutdown);
        } catch (...) { }
    }
    std::wstring invoke(const std::wstring& request, int64_t generation, const std::wstring& source, bool async) override {
        AttachedEnv scope(vm);
        auto env = scope.env;
        try {
            LocalFrame frame(env);
            auto stringType = env->FindClass("java/lang/String");
            checkJava(env);
            auto args = env->NewObjectArray(4, stringType, nullptr);
            checkJava(env);
            const std::wstring values[] = {request, std::to_wstring(generation), source, async ? L"true" : L"false"};
            for (jsize i = 0; i < 4; ++i) {
                auto value = string(env, values[i]);
                checkJava(env);
                env->SetObjectArrayElement(args, i, value);
                checkJava(env);
            }
            auto result = env->CallObjectMethod(invocation, invokeMethod, args);
            checkJava(env);
            if (!result || !env->IsInstanceOf(result, stringType))
                throw std::runtime_error("Bridge callback must return String");
            auto output = wide(env, static_cast<jstring>(result));
            checkJava(env);
            return output;
        } catch (...) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            throw;
        }
    }
    void navigated(int64_t generation) override {
        AttachedEnv scope(vm);
        scope.env->CallVoidMethod(navigation, navigationMethod, static_cast<jlong>(generation));
        if (scope.env->ExceptionCheck()) { scope.env->ExceptionClear(); throw std::runtime_error("Bridge navigation failed"); }
    }
    void closed() override {
        AttachedEnv scope(vm);
        scope.env->CallVoidMethod(shutdown, closeMethod);
        if (scope.env->ExceptionCheck()) scope.env->ExceptionClear();
    }
};
}
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(createBound)(JNIEnv* env, jobject, jlong parent, jstring profile, jobject callback, jstring script, jint colorScheme) {
    try { return browser::create(parent, wide(env, profile), std::make_shared<JavaBridge>(env, callback), wide(env, script), colorScheme); }
    catch (...) { if (!env->ExceptionCheck()) error(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(bridgeReply)(JNIEnv* env, jobject, jlong id, jlong generation, jstring json) {
    try { browser::bridgeReply(id, generation, wide(env, json)); } catch (...) { error(env); }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(colorScheme)(JNIEnv* env, jobject, jlong id, jint value) {
    try { browser::colorScheme(id, value); } catch (...) { error(env); }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(configure)(JNIEnv* env, jobject, jlong id,
    jstring userAgent, jboolean javaScript, jdouble zoom, jboolean userZoom,
    jboolean devTools, jboolean contextMenu, jboolean statusBar) {
    try {
        browser::Settings settings;
        if (userAgent) settings.userAgent = wide(env, userAgent);
        settings.javaScript = javaScript != 0;
        settings.zoom = zoom;
        settings.userZoom = userZoom != 0;
        settings.devTools = devTools != 0;
        settings.contextMenu = contextMenu != 0;
        settings.statusBar = statusBar != 0;
        browser::configure(id, std::move(settings));
    } catch (...) { error(env); }
}

namespace {
struct JavaClient {
    JavaVM* vm{}; jobject callback{}; jmethodID apply{};
    JavaClient(JNIEnv* env, jobject value) {
        env->GetJavaVM(&vm);
        auto type = env->FindClass("java/util/function/Function");
        checkJava(env);
        if (!value || !env->IsInstanceOf(value, type)) throw std::invalid_argument("Expected Function");
        apply = env->GetMethodID(type, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;");
        checkJava(env);
        callback = env->NewGlobalRef(value);
        if (!callback) throw std::bad_alloc();
    }
    ~JavaClient() { try { AttachedEnv scope(vm); scope.env->DeleteGlobalRef(callback); } catch (...) {} }
    std::wstring call(const std::vector<std::wstring>& values) {
        AttachedEnv scope(vm); auto env = scope.env;
        try {
            LocalFrame frame(env);
            auto type = env->FindClass("java/lang/String"); checkJava(env);
            auto args = env->NewObjectArray(static_cast<jsize>(values.size()), type, nullptr); checkJava(env);
            for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
                auto value = string(env, values[i]); checkJava(env); env->SetObjectArrayElement(args, i, value); checkJava(env);
            }
            auto result = env->CallObjectMethod(callback, apply, args); checkJava(env);
            if (!result || !env->IsInstanceOf(result, type)) throw std::runtime_error("Invalid client response");
            return wide(env, static_cast<jstring>(result));
        } catch (...) { if (env->ExceptionCheck()) env->ExceptionClear(); throw; }
    }
};
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(setClient)(JNIEnv* env, jobject, jlong id, jobject callback) {
    try {
        auto client = std::make_shared<JavaClient>(env, callback);
        browser::setClient(id, [client](const auto& args) { return client->call(args); });
    } catch (...) { if (!env->ExceptionCheck()) error(env); }
}
extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(pageState)(JNIEnv* env, jobject, jlong id) {
    try {
        auto values = browser::pageState(id);
        auto type = env->FindClass("java/lang/String"); checkJava(env);
        auto result = env->NewObjectArray(static_cast<jsize>(values.size()), type, nullptr); checkJava(env);
        for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
            auto value = string(env, values[i]); checkJava(env);
            env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value); checkJava(env);
        }
        return result;
    } catch (...) { if (!env->ExceptionCheck()) error(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(navigationAction)(JNIEnv* env, jobject, jlong id, jint action) {
    try { browser::navigationAction(id, action); } catch (...) { error(env); }
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(isClosed)(JNIEnv*, jobject, jlong id) { return browser::isClosed(id); }
extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(errorState)(JNIEnv* env, jobject, jlong id) {
    try {
        auto values = browser::errorState(id);
        auto type = env->FindClass("java/lang/String"); checkJava(env);
        auto result = env->NewObjectArray(static_cast<jsize>(values.size()), type, nullptr); checkJava(env);
        for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
            auto value = string(env, values[i]); checkJava(env);
            env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value); checkJava(env);
        }
        return result;
    } catch (...) { if (!env->ExceptionCheck()) error(env); return nullptr; }
}

extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(closeAndWait)(JNIEnv* env, jobject, jlong id) {
    try {
        auto values = browser::closeAndWait(id);
        auto type = env->FindClass("java/lang/String"); checkJava(env);
        auto result = env->NewObjectArray(static_cast<jsize>(values.size()), type, nullptr); checkJava(env);
        for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
            auto value = string(env, values[i]); checkJava(env);
            env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value); checkJava(env);
        }
        return result;
    } catch (...) { if (!env->ExceptionCheck()) error(env); return nullptr; }
}
