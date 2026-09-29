#include "webview_host.h"
#include "bridge_dispatch.h"
#include <windows.h>
#include <d3d11.h>
#include <DispatcherQueue.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.System.h>
#include <winrt/Windows.UI.Composition.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <wrl.h>
#include <WebView2.h>
#include <atomic>
#include <cmath>
#include <cwchar>
#include <deque>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <stdexcept>

using Microsoft::WRL::ComPtr;
using Microsoft::WRL::Callback;
using namespace winrt;
using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;
using namespace winrt::Windows::Graphics::DirectX::Direct3D11;
using namespace winrt::Windows::UI::Composition;

namespace browser {
namespace {
constexpr UINT wakeMessage = WM_APP + 37;
std::atomic<int> liveHosts{};
struct Host : std::enable_shared_from_this<Host> {
    Host() { ++liveHosts; }
    ~Host() { --liveHosts; }
    HWND parent{}, ownedWindow{};
    std::wstring profile;
    ClientCallback client;
    bool finished{}; // guarded by registryMutex
    std::promise<void> closedPromise;
    std::shared_future<void> closedFuture = closedPromise.get_future().share();
    std::vector<std::wstring> error;
    std::vector<std::wstring> page = {L"", L"", L"false", L"false", L"false"};
    bool loading{};
    UINT64 navigationId{};
    std::wstring navigationUrl;
    std::shared_ptr<BridgeCallbacks> bridge;
    std::wstring initializationScript;
    int64_t generation{};
    bool documentActive{};
    std::mutex mutex;
    std::deque<std::function<void()>> commands;
    std::wstring message = L"Initializing WebView2", evaluation;
    std::vector<uint8_t> pixels;
    int frameWidth{}, frameHeight{};
    int64_t viewportRevision{}, frameViewportRevision{};
    double frameScale = 1;
    int64_t viewportConfirmedAfter{};
    bool viewportCheckPending{};
    int64_t sequence{};
    std::atomic<DWORD> threadId{};
    std::atomic<bool> stopping{};
    std::atomic<int> focusMove{};
    std::atomic<bool> ready{};
    bool captureReady{};
    int x{}, y{}, width = 640, height = 480;
    double scale = 1;
    std::wstring pendingNavigation;
    bool pendingHtml{};
    int preferredColorScheme{};
    Settings options;
    std::wstring defaultUserAgent, appliedUserAgent;
    double appliedZoom = 0;
    ComPtr<ICoreWebView2Environment> environment;
    ComPtr<ICoreWebView2CompositionController> composition;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> webview;
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<ID3D11Texture2D> staging;
    winrt::Windows::System::DispatcherQueueController dispatcher{nullptr};
    Compositor compositor{nullptr};
    ContainerVisual root{nullptr}, visual{nullptr};
    IDirect3DDevice captureDevice{nullptr};
    GraphicsCaptureItem item{nullptr};
    Direct3D11CaptureFramePool pool{nullptr};
    GraphicsCaptureSession capture{nullptr};
    event_token frameToken{};

    void report(const std::wstring& text) {
        std::lock_guard lock(mutex); message = text;
        if (text.rfind(L"Error", 0) == 0)
            error = {ready ? L"Operation" : L"Initialization", text, L"", L"", L"", L""};
    }
    void fail(const std::wstring& stage, const std::wstring& text, const std::wstring& hr = L"",
              const std::wstring& url = L"", const std::wstring& navigation = L"", const std::wstring& process = L"") {
        std::lock_guard lock(mutex);
        message = L"Error: " + text;
        error = {stage, text, hr, url, navigation, process};
    }
    void failure(const hresult_error& value, const std::wstring& stage = L"") {
        fail(stage.empty() ? (ready ? L"Operation" : L"Initialization") : stage,
             std::wstring(value.message()), std::to_wstring(static_cast<uint32_t>(value.code().value)));
    }
    void clearNavigationError() {
        std::lock_guard lock(mutex);
        if (!error.empty() && error[0] == L"Navigation") error.clear();
    }
    void post(std::function<void()> action) {
        { std::lock_guard lock(mutex); if (stopping) return; commands.push_back(std::move(action)); }
        if (auto tid = threadId.load()) PostThreadMessageW(tid, wakeMessage, 0, 0);
    }
    void drain() {
        std::deque<std::function<void()>> work;
        { std::lock_guard lock(mutex); work.swap(commands); }
        for (auto& action : work) {
            if (stopping) break;
            try { action(); } catch (const hresult_error& error) { failure(error); }
            catch (...) { report(L"Error: native command failed"); }
        }
    }
    void updatePage() {
        if (!webview) return;
        LPWSTR url{}, title{};
        BOOL back{}, forward{};
        check_hresult(webview->get_Source(&url));
        std::wstring uri = url ? url : L"";
        CoTaskMemFree(url);
        check_hresult(webview->get_DocumentTitle(&title));
        std::wstring name = title ? title : L"";
        CoTaskMemFree(title);
        check_hresult(webview->get_CanGoBack(&back));
        check_hresult(webview->get_CanGoForward(&forward));
        std::lock_guard lock(mutex);
        page = {uri, name, loading ? L"true" : L"false", back ? L"true" : L"false", forward ? L"true" : L"false"};
    }
    void applySettings() {
        if (!webview) return;
        ComPtr<ICoreWebView2Settings> settings;
        check_hresult(webview->get_Settings(&settings));
        ComPtr<ICoreWebView2Settings2> settings2;
        check_hresult(settings.As(&settings2));
        if (defaultUserAgent.empty()) {
            LPWSTR ua{};
            check_hresult(settings2->get_UserAgent(&ua));
            defaultUserAgent = ua ? ua : L"";
            CoTaskMemFree(ua);
        }
        // Avoid overriding the default UA (and changing Client Hints) unless requested.
        if (appliedUserAgent != options.userAgent) {
            check_hresult(settings2->put_UserAgent((options.userAgent.empty() ? defaultUserAgent : options.userAgent).c_str()));
            appliedUserAgent = options.userAgent;
        }
        check_hresult(settings->put_IsScriptEnabled(options.javaScript));
        check_hresult(settings->put_IsZoomControlEnabled(options.userZoom));
        check_hresult(settings->put_AreDevToolsEnabled(options.devTools));
        check_hresult(settings->put_AreDefaultContextMenusEnabled(options.contextMenu));
        check_hresult(settings->put_IsStatusBarEnabled(options.statusBar));
        if (appliedZoom != options.zoom) {
            check_hresult(controller->put_ZoomFactor(options.zoom));
            appliedZoom = options.zoom;
        }
    }
    void applyColorScheme() {
        if (!webview) return;
        ComPtr<ICoreWebView2_13> view13;
        check_hresult(webview.As(&view13));
        ComPtr<ICoreWebView2Profile> browserProfile;
        check_hresult(view13->get_Profile(&browserProfile));
        const auto scheme = preferredColorScheme == 1 ? COREWEBVIEW2_PREFERRED_COLOR_SCHEME_LIGHT
            : preferredColorScheme == 2 ? COREWEBVIEW2_PREFERRED_COLOR_SCHEME_DARK
            : COREWEBVIEW2_PREFERRED_COLOR_SCHEME_AUTO;
        check_hresult(browserProfile->put_PreferredColorScheme(scheme));
    }
    void bounds() {
        if (!controller) return;
        // Bounds retain the actual client position for native input. WebView's
        // visual content starts at its own origin; no desktop target is attached.
        check_hresult(controller->put_Bounds({x, y, x + width, y + height}));
        visual.Offset({0, 0, 0});
        root.Size({static_cast<float>(width), static_cast<float>(height)});
        visual.Size(root.Size());
        ComPtr<ICoreWebView2Controller3> controller3;
        if (SUCCEEDED(controller.As(&controller3))) {
            controller3->put_ShouldDetectMonitorScaleChanges(FALSE);
            controller3->put_RasterizationScale(scale);
        }
        controller->NotifyParentWindowPositionChanged();
    }
    void verifyViewport() {
        if (!ready || !webview || viewportCheckPending || viewportConfirmedAfter) return;
        viewportCheckPending = true;
        const auto revision = viewportRevision;
        auto weak = weak_from_this();
        check_hresult(webview->ExecuteScript(L"[innerWidth,innerHeight,devicePixelRatio]",
            Callback<ICoreWebView2ExecuteScriptCompletedHandler>([weak, revision](HRESULT hr, LPCWSTR result) -> HRESULT {
                auto h = weak.lock(); if (!h || h->stopping || revision != h->viewportRevision) return S_OK;
                h->viewportCheckPending = false;
                double w{}, height{}, dpr{}, zoom{};
                if (FAILED(hr) || !result || swscanf_s(result, L"[%lf,%lf,%lf]", &w, &height, &dpr) != 3) return S_OK;
                if (FAILED(h->controller->get_ZoomFactor(&zoom))) return S_OK;
                const double expected = h->scale * zoom;
                if (std::abs(w - h->width / expected) <= 1.1 && std::abs(height - h->height / expected) <= 1.1 && std::abs(dpr - expected) < 0.01) {
                    LARGE_INTEGER now{}, frequency{}; QueryPerformanceCounter(&now); QueryPerformanceFrequency(&frequency);
                    h->viewportConfirmedAfter = static_cast<int64_t>(static_cast<double>(now.QuadPart) * 10000000.0 / frequency.QuadPart);
                    // Static pages may produce no further invalidation after the viewport acknowledgement.
                    // Restart capture to request a fresh snapshot of the confirmed visual tree.
                    try { h->stopCapture(); h->startCapture(); }
                    catch (const hresult_error& e) { h->failure(e, L"Rendering"); }
                    catch (...) { h->fail(L"Rendering", L"capture restart failed"); }
                }
                return S_OK;
            }).Get()));
    }
    template<class Action> void cleanup(Action action) noexcept {
        try { action(); }
        catch (const hresult_error& e) { failure(e, L"Closing"); }
        catch (...) { fail(L"Closing", L"native cleanup operation failed"); }
    }
    void stopCapture() {
        cleanup([&] { if (pool) pool.FrameArrived(frameToken); });
        cleanup([&] { if (capture) capture.Close(); }); capture = nullptr;
        cleanup([&] { if (pool) pool.Close(); }); pool = nullptr;
        item = nullptr;
        captureReady = false;
        staging.Reset(); context.Reset(); device.Reset(); captureDevice = nullptr;
    }
    void startCapture() {
        check_hresult(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION,
            &device, nullptr, &context));
        ComPtr<IDXGIDevice> dxgi;
        check_hresult(device.As(&dxgi));
        com_ptr<IInspectable> inspectable;
        check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.Get(), inspectable.put()));
        captureDevice = inspectable.as<IDirect3DDevice>();
        item = GraphicsCaptureItem::CreateFromVisual(root);
        pool = Direct3D11CaptureFramePool::Create(captureDevice,
            DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, {width, height});
        auto weak = weak_from_this();
        frameToken = pool.FrameArrived([weak](auto const&, auto const&) {
            if (auto self = weak.lock(); self && !self->stopping) {
                try { self->receiveFrame(); }
                catch (const hresult_error& error) { self->failure(error, L"Rendering"); }
                catch (...) { self->fail(L"Rendering", L"Capture frame processing failed"); }
            }
        });
        capture = pool.CreateCaptureSession(item);
        capture.IsCursorCaptureEnabled(false);
        capture.StartCapture();
        captureReady = true;
    }
    void receiveFrame() {
        auto incoming = pool.TryGetNextFrame();
        if (!incoming) return;
        verifyViewport();
        if (!viewportConfirmedAfter || incoming.SystemRelativeTime().count() <= viewportConfirmedAfter) return;
        const auto size = incoming.ContentSize();
        if (size.Width != width || size.Height != height) return;
        if (size.Width <= 0 || size.Height <= 0 || size.Width > 8192 || size.Height > 8192) return;
        const auto access = incoming.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
        ComPtr<ID3D11Texture2D> texture;
        check_hresult(access->GetInterface(IID_PPV_ARGS(&texture)));
        D3D11_TEXTURE2D_DESC desc{};
        texture->GetDesc(&desc);
        if (desc.Width < static_cast<UINT>(size.Width) || desc.Height < static_cast<UINT>(size.Height)) {
            incoming.Close();
            pool.Recreate(captureDevice, DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size);
            return;
        }
        D3D11_TEXTURE2D_DESC previous{};
        if (staging) staging->GetDesc(&previous);
        if (!staging || previous.Width != desc.Width || previous.Height != desc.Height) {
            staging.Reset();
            desc.Usage = D3D11_USAGE_STAGING;
            desc.BindFlags = 0;
            desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
            desc.MiscFlags = 0;
            check_hresult(device->CreateTexture2D(&desc, nullptr, &staging));
        }
        context->CopyResource(staging.Get(), texture.Get());
        D3D11_MAPPED_SUBRESOURCE mapped{};
        check_hresult(context->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped));
        {
            std::lock_guard lock(mutex);
            try {
                pixels.resize(static_cast<size_t>(size.Width) * size.Height * 4);
                for (int row = 0; row < size.Height; ++row)
                    memcpy(pixels.data() + static_cast<size_t>(row) * size.Width * 4,
                        static_cast<const uint8_t*>(mapped.pData) + static_cast<size_t>(row) * mapped.RowPitch,
                        static_cast<size_t>(size.Width) * 4);
                frameViewportRevision = viewportRevision;
                frameScale = scale;
                frameWidth = size.Width;
                frameHeight = size.Height;
                ++sequence;
            } catch (...) { context->Unmap(staging.Get(), 0); throw; }
        }
        context->Unmap(staging.Get(), 0);
        incoming.Close();
        if (static_cast<int>(desc.Width) != size.Width || static_cast<int>(desc.Height) != size.Height)
            pool.Recreate(captureDevice, DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size);
    }
    void initialize() {
        DispatcherQueueOptions options{sizeof(DispatcherQueueOptions), DQTYPE_THREAD_CURRENT, DQTAT_COM_STA};
        check_hresult(CreateDispatcherQueueController(options,
            reinterpret_cast<ABI::Windows::System::IDispatcherQueueController**>(put_abi(dispatcher))));
        compositor = Compositor();
        root = compositor.CreateContainerVisual();
        visual = compositor.CreateContainerVisual();
        root.Children().InsertAtTop(visual);
        if (!parent) {
            ownedWindow = CreateWindowExW(0, L"STATIC", L"Compose WebView2 capture host",
                WS_POPUP, 0, 0, width, height, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
            if (!ownedWindow) throw_last_error();
            parent = ownedWindow;
        }
        const auto weak = weak_from_this();
        check_hresult(CreateCoreWebView2EnvironmentWithOptions(nullptr, profile.c_str(), nullptr,
            Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
                [weak](HRESULT result, ICoreWebView2Environment* env) -> HRESULT {
                    auto self = weak.lock();
                    if (!self || self->stopping) return S_OK;
                    try {
                        check_hresult(result);
                        self->environment = env;
                        ComPtr<ICoreWebView2Environment3> env3;
                        check_hresult(env->QueryInterface(IID_PPV_ARGS(&env3)));
                        check_hresult(env3->CreateCoreWebView2CompositionController(self->parent,
                            Callback<ICoreWebView2CreateCoreWebView2CompositionControllerCompletedHandler>(
                                [weak](HRESULT result2, ICoreWebView2CompositionController* cc) -> HRESULT {
                                    auto host = weak.lock();
                                    if (!host || host->stopping) { if (cc) { ComPtr<ICoreWebView2Controller> c; cc->QueryInterface(IID_PPV_ARGS(&c)); if (c) c->Close(); } return S_OK; }
                                    try {
                                        check_hresult(result2);
                                        host->composition = cc;
                                        check_hresult(cc->QueryInterface(IID_PPV_ARGS(&host->controller)));
                                        check_hresult(host->controller->get_CoreWebView2(&host->webview));
                                        check_hresult(cc->put_RootVisualTarget(winrt::get_unknown(host->visual)));
                                        host->applyColorScheme();
                                        host->bounds();
                                        check_hresult(host->controller->put_IsVisible(TRUE));
                                        host->applySettings();
                                        EventRegistrationToken token{};
                                        check_hresult(host->controller->add_AcceleratorKeyPressed(Callback<ICoreWebView2AcceleratorKeyPressedEventHandler>(
                                            [weak](auto*, ICoreWebView2AcceleratorKeyPressedEventArgs* args) -> HRESULT {
                                                auto h = weak.lock(); if (!h || h->stopping || !h->client) return S_OK;
                                                try {
                                                    UINT key{}; COREWEBVIEW2_KEY_EVENT_KIND kind{};
                                                    COREWEBVIEW2_PHYSICAL_KEY_STATUS physical{};
                                                    check_hresult(args->get_VirtualKey(&key)); check_hresult(args->get_KeyEventKind(&kind));
                                                    check_hresult(args->get_PhysicalKeyStatus(&physical));
                                                    const bool down = kind == COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN || kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN;
                                                    const bool system = kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN || kind == COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_UP;
                                                    if (h->client({L"key", std::to_wstring(key), down ? L"true" : L"false", system ? L"true" : L"false",
                                                        down && physical.WasKeyDown ? L"true" : L"false", GetKeyState(VK_CONTROL) & 0x8000 ? L"true" : L"false",
                                                        GetKeyState(VK_MENU) & 0x8000 ? L"true" : L"false", GetKeyState(VK_SHIFT) & 0x8000 ? L"true" : L"false"}) == L"handled")
                                                        check_hresult(args->put_Handled(TRUE));
                                                } catch (...) { args->put_Handled(TRUE); h->fail(L"Callback", L"Accelerator callback failed"); }
                                                return S_OK;
                                            }).Get(), &token));
                                        host->controller->add_MoveFocusRequested(Callback<ICoreWebView2MoveFocusRequestedEventHandler>(
                                            [weak](auto*, ICoreWebView2MoveFocusRequestedEventArgs* args) -> HRESULT {
                                                if (auto h = weak.lock(); h && !h->stopping) {
                                                    COREWEBVIEW2_MOVE_FOCUS_REASON reason{};
                                                    args->get_Reason(&reason);
                                                    h->focusMove = reason == COREWEBVIEW2_MOVE_FOCUS_REASON_PREVIOUS ? -1 : 1;
                                                    args->put_Handled(TRUE);
                                                }
                                                return S_OK;
                                            }).Get(), &token);
                                        check_hresult(host->webview->add_NavigationStarting(Callback<ICoreWebView2NavigationStartingEventHandler>(
                                            [weak](auto*, ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT {
                                                auto h = weak.lock();
                                                if (!h || h->stopping) { args->put_Cancel(TRUE); return S_OK; }
                                                try {
                                                    LPWSTR raw{}; check_hresult(args->get_Uri(&raw));
                                                    std::wstring uri = raw ? raw : L""; CoTaskMemFree(raw);
                                                    BOOL user{}, redirect{};
                                                    args->get_IsUserInitiated(&user); args->get_IsRedirected(&redirect);
                                                    if (h->client && h->client({L"navigation", uri, user ? L"true" : L"false", redirect ? L"true" : L"false"}) != L"allow") {
                                                        args->put_Cancel(TRUE);
                                                        return S_OK;
                                                    }
                                                    args->get_NavigationId(&h->navigationId);
                                                    h->navigationUrl = uri;
                                                    h->clearNavigationError();
                                                    h->loading = true;
                                                    h->documentActive = false;
                                                    ++h->generation;
                                                    if (h->bridge) h->bridge->navigated(h->generation);
                                                    h->updatePage(); h->report(L"Loading");
                                                } catch (...) {
                                                    args->put_Cancel(TRUE); h->loading = false;
                                                    try { h->updatePage(); } catch (...) {}
                                                    h->fail(L"Callback", L"navigation callback failed");
                                                }
                                                return S_OK;
                                            }).Get(), &token));
                                        check_hresult(host->webview->add_SourceChanged(Callback<ICoreWebView2SourceChangedEventHandler>(
                                            [weak](auto*, auto*) -> HRESULT {
                                                if (auto h = weak.lock(); h && !h->stopping) { try { h->updatePage(); } catch (...) { h->report(L"Error: source update failed"); } }
                                                return S_OK;
                                            }).Get(), &token));
                                        check_hresult(host->webview->add_DocumentTitleChanged(Callback<ICoreWebView2DocumentTitleChangedEventHandler>(
                                            [weak](auto*, auto*) -> HRESULT {
                                                if (auto h = weak.lock(); h && !h->stopping) { try { h->updatePage(); } catch (...) { h->report(L"Error: title update failed"); } }
                                                return S_OK;
                                            }).Get(), &token));
                                        check_hresult(host->webview->add_HistoryChanged(Callback<ICoreWebView2HistoryChangedEventHandler>(
                                            [weak](auto*, auto*) -> HRESULT {
                                                if (auto h = weak.lock(); h && !h->stopping) { try { h->updatePage(); } catch (...) { h->report(L"Error: history update failed"); } }
                                                return S_OK;
                                            }).Get(), &token));
                                        check_hresult(host->webview->add_NewWindowRequested(Callback<ICoreWebView2NewWindowRequestedEventHandler>(
                                            [weak](auto*, ICoreWebView2NewWindowRequestedEventArgs* args) -> HRESULT {
                                                args->put_Handled(TRUE);
                                                auto h = weak.lock();
                                                if (!h || h->stopping || !h->client) return S_OK;
                                                try {
                                                    LPWSTR raw{}; check_hresult(args->get_Uri(&raw));
                                                    std::wstring uri = raw ? raw : L""; CoTaskMemFree(raw);
                                                    BOOL user{}; args->get_IsUserInitiated(&user);
                                                    if (h->client({L"newWindow", uri, user ? L"true" : L"false"}) == L"current") {
                                                        h->post([weak, uri] { if (auto live = weak.lock(); live && !live->stopping) {
                                                            live->pendingNavigation = uri; live->pendingHtml = false; live->load();
                                                        } });
                                                    }
                                                } catch (...) { h->fail(L"Callback", L"new window callback failed"); }
                                                return S_OK;
                                            }).Get(), &token));
                                        check_hresult(host->webview->add_ContentLoading(Callback<ICoreWebView2ContentLoadingEventHandler>(
                                            [weak](auto*, auto*) -> HRESULT {
                                                if (auto h = weak.lock(); h && !h->stopping) h->documentActive = true;
                                                return S_OK;
                                            }).Get(), &token));
                                        host->webview->add_NavigationCompleted(Callback<ICoreWebView2NavigationCompletedEventHandler>(
                                            [weak](auto*, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                                                if (auto h = weak.lock(); h && !h->stopping) {
                                                    UINT64 id{}; args->get_NavigationId(&id);
                                                    if (id != h->navigationId) return S_OK;
                                                    try {
                                                        h->loading = false; h->updatePage();
                                                        BOOL ok{}; args->get_IsSuccess(&ok);
                                                        COREWEBVIEW2_WEB_ERROR_STATUS error{}; args->get_WebErrorStatus(&error);
                                                        if (ok || error == COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED) h->report(L"Ready");
                                                        else h->fail(L"Navigation", L"navigation failed", L"", h->navigationUrl,
                                                            std::to_wstring(static_cast<int>(error)));
                                                    } catch (...) { h->report(L"Error: navigation completion failed"); }
                                                } return S_OK;
                                            }).Get(), &token);
                                        host->webview->add_ProcessFailed(Callback<ICoreWebView2ProcessFailedEventHandler>(
                                            [weak](auto*, ICoreWebView2ProcessFailedEventArgs* args) -> HRESULT {
                                                if (auto h = weak.lock(); h && !h->stopping) {
                                                    h->documentActive = false;
                                                    try { if (h->bridge) h->bridge->navigated(++h->generation); } catch (...) { }
                                                    h->loading = false;
                                                    try { h->updatePage(); } catch (...) {}
                                                    COREWEBVIEW2_PROCESS_FAILED_KIND kind{}; args->get_ProcessFailedKind(&kind);
                                                    h->fail(L"BrowserProcess", L"WebView2 browser process failed", L"", L"", L"", std::to_wstring(static_cast<int>(kind)));
                                                }
                                                return S_OK;
                                            }).Get(), &token);
                                        host->startCapture();
                                        host->installBridge();
                                    } catch (const hresult_error& error) { host->failure(error); }
                                    catch (...) { host->report(L"Error: controller initialization failed"); }
                                    return S_OK;
                                }).Get()));
                    } catch (const hresult_error& error) { self->failure(error); }
                    catch (...) { self->report(L"Error: environment initialization failed"); }
                    return S_OK;
                }).Get()));
    }
    std::wstring source() {
        LPWSTR value{};
        check_hresult(webview->get_Source(&value));
        std::wstring result = value ? value : L"";
        CoTaskMemFree(value);
        return result;
    }
    void completeInitialization() {
        ready = true;
        report(L"Ready");
        if (!pendingNavigation.empty()) load();
    }
    void installBridge() {
        if (!bridge) { completeInitialization(); return; }
        const auto weak = weak_from_this();
        ComPtr<IDispatch> dispatch;
        dispatch.Attach(new BridgeDispatch([weak](const std::wstring& request) {
            auto h = weak.lock();
            if (!h || h->stopping || !h->documentActive) throw std::runtime_error("Page closed");
            return h->bridge->invoke(request, h->generation, h->source(), false);
        }));
        VARIANT value{};
        value.vt = VT_DISPATCH;
        value.pdispVal = dispatch.Get();
        check_hresult(webview->AddHostObjectToScript(L"__composeBridge", &value));
        EventRegistrationToken token{};
        check_hresult(webview->add_WebMessageReceived(Callback<ICoreWebView2WebMessageReceivedEventHandler>(
            [weak](auto*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                auto h = weak.lock();
                if (!h || h->stopping || !h->documentActive) return S_OK;
                LPWSTR text{}, uri{};
                const auto hr = args->TryGetWebMessageAsString(&text);
                args->get_Source(&uri);
                std::wstring request = text ? text : L"", source = uri ? uri : L"";
                CoTaskMemFree(text); CoTaskMemFree(uri);
                if (FAILED(hr)) return S_OK;
                try { if (source == h->source()) h->bridge->invoke(request, h->generation, source, true); }
                catch (...) { h->fail(L"Callback", L"bridge message callback failed"); }
                return S_OK;
            }).Get(), &token));
        check_hresult(webview->AddScriptToExecuteOnDocumentCreated(initializationScript.c_str(),
            Callback<ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler>(
                [weak](HRESULT hr, LPCWSTR) -> HRESULT {
                    if (auto h = weak.lock(); h && !h->stopping) {
                        try { check_hresult(hr); h->completeInitialization(); }
                        catch (const hresult_error& error) { h->failure(error); }
                    }
                    return S_OK;
                }).Get()));
    }
    void load() {
        if (!ready) return;
        check_hresult(pendingHtml ? webview->NavigateToString(pendingNavigation.c_str()) : webview->Navigate(pendingNavigation.c_str()));
    }
    void teardown() {
        cleanup([&] { if (bridge) bridge->closed(); });
        { std::lock_guard lock(mutex); commands.clear(); pixels.clear(); }
        stopCapture();
        cleanup([&] { if (composition) check_hresult(composition->put_RootVisualTarget(nullptr)); });
        cleanup([&] { if (controller) check_hresult(controller->Close()); });
        webview.Reset(); controller.Reset(); composition.Reset(); environment.Reset();
        bridge.reset(); client = {};
        cleanup([&] { if (root) root.Children().RemoveAll(); });
        visual = nullptr; root = nullptr;
        cleanup([&] { if (compositor) compositor.Close(); }); compositor = nullptr;
        captureDevice = nullptr; staging.Reset(); context.Reset(); device.Reset();
        if (ownedWindow) { DestroyWindow(ownedWindow); ownedWindow = nullptr; }
        cleanup([&] { if (dispatcher) {
            auto done = dispatcher.ShutdownQueueAsync();
            while (done.Status() == winrt::Windows::Foundation::AsyncStatus::Started) {
                MSG msg{};
                while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) { TranslateMessage(&msg); DispatchMessageW(&msg); }
                MsgWaitForMultipleObjects(0, nullptr, FALSE, 10, QS_ALLINPUT);
            }
        } });
        dispatcher = nullptr;
    }
    void run() {
        const HRESULT initialized = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        if (FAILED(initialized)) { report(L"Error: STA initialization failed"); return; }
        MSG msg{};
        PeekMessageW(&msg, nullptr, 0, 0, PM_NOREMOVE);
        threadId = GetCurrentThreadId();
        const auto initializationTimer = SetTimer(nullptr, 0, 30000, nullptr);
        try {
            if (!stopping) initialize();
            drain();
            while (!stopping && GetMessageW(&msg, nullptr, 0, 0) > 0) {
                if (msg.message == WM_TIMER && msg.wParam == initializationTimer) {
                    KillTimer(nullptr, initializationTimer);
                    if (!ready) throw hresult_error(HRESULT_FROM_WIN32(ERROR_TIMEOUT), L"WebView2 initialization timed out after 30 seconds");
                }
                else if (msg.message == wakeMessage) drain();
                else { TranslateMessage(&msg); DispatchMessageW(&msg); }
            }
        } catch (const hresult_error& error) { failure(error); }
        catch (...) { report(L"Error: native host failed"); }
        if (initializationTimer) KillTimer(nullptr, initializationTimer);
        try { teardown(); } catch (...) { fail(L"Closing", L"native teardown failed"); }
        threadId = 0;
        CoUninitialize();
    }
};
std::mutex registryMutex;
std::map<int64_t, std::shared_ptr<Host>> registry;
std::atomic<int64_t> nextId{1};
std::shared_ptr<Host> find(int64_t id) {
    std::lock_guard lock(registryMutex);
    auto it = registry.find(id);
    return it == registry.end() ? nullptr : it->second;
}
}
int64_t create(int64_t parent, const std::wstring& profile, std::shared_ptr<BridgeCallbacks> bridge, const std::wstring& initializationScript, int colorScheme) {
    auto host = std::make_shared<Host>();
    host->parent = reinterpret_cast<HWND>(parent);
    host->profile = profile;
    host->preferredColorScheme = colorScheme;
    host->bridge = std::move(bridge);
    host->initializationScript = initializationScript;
    const auto id = nextId++;
    { std::lock_guard lock(registryMutex); registry.emplace(id, host); }
    try { std::thread([host, id] {
        host->run();
        { std::lock_guard lock(registryMutex);
          host->finished = true;
          if (host->stopping) registry.erase(id);
        }
        host->closedPromise.set_value();
    }).detach(); }
    catch (...) { std::lock_guard lock(registryMutex); registry.erase(id); throw; }
    return id;
}
void setClient(int64_t id, ClientCallback callback) {
    if (auto h = find(id)) h->post([h, callback = std::move(callback)] { h->client = callback; });
}
std::vector<std::wstring> pageState(int64_t id) {
    if (auto h = find(id)) { std::lock_guard lock(h->mutex); auto result = h->page; result.push_back(h->ready ? L"true" : L"false"); return result; }
    return {L"", L"", L"false", L"false", L"false"};
}
void navigationAction(int64_t id, int action) {
    if (auto h = find(id)) h->post([h, action] {
        if (!h->ready) { if (action == 2) h->pendingNavigation.clear(); return; }
        BOOL allowed{};
        switch (action) {
        case 0: check_hresult(h->webview->get_CanGoBack(&allowed)); if (allowed) check_hresult(h->webview->GoBack()); break;
        case 1: check_hresult(h->webview->get_CanGoForward(&allowed)); if (allowed) check_hresult(h->webview->GoForward()); break;
        case 2: check_hresult(h->webview->Stop()); h->loading = false; h->updatePage(); h->report(L"Ready"); break;
        case 3: check_hresult(h->webview->Reload()); break;
        default: throw std::invalid_argument("Invalid navigation action");
        }
    });
}
int activeHosts() { return liveHosts.load(); }
void bridgeReply(int64_t id, int64_t generation, const std::wstring& json) {
    if (auto h = find(id)) h->post([h, generation, json] {
        if (h->webview && h->documentActive && generation == h->generation)
            check_hresult(h->webview->PostWebMessageAsJson(json.c_str()));
    });
}
void configure(int64_t id, Settings settings) {
    if (!std::isfinite(settings.zoom) || settings.zoom <= 0) throw std::invalid_argument("Invalid zoom factor");
    if (auto h = find(id)) h->post([h, settings = std::move(settings)] {
        h->options = settings;
        h->applySettings();
    });
}
void colorScheme(int64_t id, int value) {
    if (value < 0 || value > 2) throw std::invalid_argument("Invalid color scheme");
    if (auto h = find(id)) h->post([h, value] {
        h->preferredColorScheme = value;
        h->applyColorScheme();
    });
}
void close(int64_t id) {
    std::shared_ptr<Host> host;
    { std::lock_guard lock(registryMutex);
      auto it = registry.find(id); if (it == registry.end()) return;
      host = it->second; host->stopping = true;
      if (host->finished) registry.erase(it);
    }
    if (auto tid = host->threadId.load()) PostThreadMessageW(tid, wakeMessage, 0, 0);
}
bool isClosed(int64_t id) { return !find(id); }
std::vector<std::wstring> closeAndWait(int64_t id) {
    auto host = find(id);
    if (!host) return {};
    close(id);
    host->closedFuture.wait();
    std::lock_guard lock(host->mutex);
    return !host->error.empty() && host->error[0] == L"Closing" ? host->error : std::vector<std::wstring>{};
}
std::vector<std::wstring> errorState(int64_t id) {
    if (auto h = find(id)) { std::lock_guard lock(h->mutex); return h->error; }
    return {};
}
void resize(int64_t id, int x, int y, int width, int height, double scale, int64_t revision) {
    if (width < 1 || height < 1 || width > 8192 || height > 8192 || !(scale >= 0.5 && scale <= 8)) return;
    if (auto h = find(id)) h->post([h, x, y, width, height, scale, revision] {
        const bool changed = h->width != width || h->height != height || h->scale != scale || (revision >= 0 && revision != h->viewportRevision);
        if (changed) {
            h->viewportRevision = revision >= 0 ? revision : h->viewportRevision + 1; h->viewportConfirmedAfter = 0; h->viewportCheckPending = false;
            std::lock_guard lock(h->mutex); h->pixels.clear();
        }
        h->x = x; h->y = y; h->width = width; h->height = height; h->scale = scale; h->bounds();
        if (changed && h->captureReady) { h->stopCapture(); h->startCapture(); }
    });
}
void navigate(int64_t id, const std::wstring& value, bool html) {
    if (auto h = find(id)) h->post([h, value, html] { h->pendingNavigation = value; h->pendingHtml = html; h->load(); });
}
void mouse(int64_t id, int message, int keys, int data, int x, int y) {
    if (auto h = find(id)) h->post([h, message, keys, data, x, y] {
        if (h->composition) {
            const bool leave = message == COREWEBVIEW2_MOUSE_EVENT_KIND_LEAVE;
            const HRESULT result = h->composition->SendMouseInput(
                static_cast<COREWEBVIEW2_MOUSE_EVENT_KIND>(message), static_cast<COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS>(leave ? 0 : keys),
                leave ? 0 : static_cast<UINT32>(data), leave ? POINT{} : POINT{x, y});
            if (FAILED(result)) h->report(L"Error: SendMouseInput message=" + std::to_wstring(message) + L" HRESULT=" + std::to_wstring(result));
        }
    });
}
void focus(int64_t id, bool enabled) {
    if (auto h = find(id)) h->post([h, enabled] {
        if (!h->controller) return;
        if (enabled) check_hresult(h->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC));
        else if (IsChild(h->parent, GetFocus())) SetFocus(h->parent);
    });
}
int takeFocusMove(int64_t id) { if (auto h = find(id)) return h->focusMove.exchange(0); return 0; }
void script(int64_t id, const std::wstring& value) {
    if (auto h = find(id)) {
        { std::lock_guard lock(h->mutex); h->evaluation.clear(); }
        h->post([h, value] {
            if (!h->webview) return;
            auto weak = h->weak_from_this();
            check_hresult(h->webview->ExecuteScript(value.c_str(), Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
                [weak](HRESULT hr, LPCWSTR result) -> HRESULT {
                    if (auto self = weak.lock(); self && !self->stopping) { std::lock_guard lock(self->mutex); self->evaluation = SUCCEEDED(hr) && result ? result : L"ERROR"; }
                    return S_OK;
                }).Get()));
        });
    }
}
std::wstring scriptResult(int64_t id) { if (auto h = find(id)) { std::lock_guard lock(h->mutex); return h->evaluation; } return L""; }
std::wstring status(int64_t id) { if (auto h = find(id)) { std::lock_guard lock(h->mutex); return h->message; } return L"Closed"; }
std::vector<uint8_t> frame(int64_t id, int64_t after) {
    if (auto h = find(id)) {
        std::lock_guard lock(h->mutex);
        if (h->sequence <= after || h->pixels.empty()) return {};
        std::vector<uint8_t> bytes(32 + h->pixels.size());
        memcpy(bytes.data(), &h->frameWidth, 4);
        memcpy(bytes.data() + 4, &h->frameHeight, 4);
        memcpy(bytes.data() + 8, &h->sequence, 8);
        memcpy(bytes.data() + 16, &h->frameViewportRevision, 8);
        memcpy(bytes.data() + 24, &h->frameScale, 8);
        memcpy(bytes.data() + 32, h->pixels.data(), h->pixels.size());
        return bytes;
    }
    return {};
}
}
