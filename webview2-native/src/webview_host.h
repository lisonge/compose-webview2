#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <memory>
#include <functional>

namespace browser {
// Opaque registry IDs, never native addresses. Commands execute on the owning STA.
struct BridgeCallbacks {
    virtual ~BridgeCallbacks() = default;
    virtual std::wstring invoke(const std::wstring& request, int64_t generation, const std::wstring& source, bool asynchronous) = 0;
    virtual void navigated(int64_t generation) = 0;
    virtual void closed() = 0;
};
int64_t create(int64_t parent, const std::wstring& profile,
    std::shared_ptr<BridgeCallbacks> bridge = {}, const std::wstring& initializationScript = L"", int colorScheme = 0);
void bridgeReply(int64_t id, int64_t generation, const std::wstring& json);
struct Settings {
    std::wstring userAgent;
    bool javaScript = true;
    double zoom = 1.0;
    bool userZoom = false, devTools = false, contextMenu = false, statusBar = false;
};
void configure(int64_t id, Settings settings);
using ClientCallback = std::function<std::wstring(const std::vector<std::wstring>&)>;
void setClient(int64_t id, ClientCallback callback);
std::vector<std::wstring> pageState(int64_t id);
void navigationAction(int64_t id, int action);
int activeHosts();
void close(int64_t id);
bool isClosed(int64_t id);
std::vector<std::wstring> closeAndWait(int64_t id);
std::vector<std::wstring> errorState(int64_t id);
void colorScheme(int64_t id, int value);
void resize(int64_t id, int x, int y, int width, int height, double scale, int64_t revision);
void navigate(int64_t id, const std::wstring& value, bool html);
void mouse(int64_t id, int message, int keys, int data, int x, int y);
void focus(int64_t id, bool enabled);
int takeFocusMove(int64_t id);
void script(int64_t id, const std::wstring& value);
std::wstring scriptResult(int64_t id);
std::wstring status(int64_t id);
std::vector<uint8_t> frame(int64_t id, int64_t after);
}
