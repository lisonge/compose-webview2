#pragma once
#include <windows.h>
#include <oaidl.h>
#include <atomic>
#include <functional>
#include <string>

// One private transport method. Public object/method names are validated in Kotlin.
class BridgeDispatch final : public IDispatch {
    std::atomic<ULONG> refs{1};
    std::function<std::wstring(const std::wstring&)> call;
public:
    explicit BridgeDispatch(std::function<std::wstring(const std::wstring&)> value) : call(std::move(value)) {}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void** result) override {
        if (!result) return E_POINTER;
        *result = nullptr;
        if (id == IID_IUnknown || id == IID_IDispatch) { *result = static_cast<IDispatch*>(this); AddRef(); return S_OK; }
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs; }
    ULONG STDMETHODCALLTYPE Release() override { auto remaining = --refs; if (!remaining) delete this; return remaining; }
    HRESULT STDMETHODCALLTYPE GetTypeInfoCount(UINT* count) override { if (!count) return E_POINTER; *count = 0; return S_OK; }
    HRESULT STDMETHODCALLTYPE GetTypeInfo(UINT, LCID, ITypeInfo**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetIDsOfNames(REFIID, LPOLESTR* names, UINT count, LCID, DISPID* ids) override {
        if (count != 1 || !names || !ids) return E_INVALIDARG;
        if (wcscmp(names[0], L"invoke") != 0) { ids[0] = DISPID_UNKNOWN; return DISP_E_UNKNOWNNAME; }
        ids[0] = 1; return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Invoke(DISPID id, REFIID, LCID, WORD flags, DISPPARAMS* args, VARIANT* result, EXCEPINFO*, UINT*) override {
        if (id != 1 || !(flags & DISPATCH_METHOD)) return DISP_E_MEMBERNOTFOUND;
        if (!args || args->cArgs != 1 || args->cNamedArgs) return DISP_E_BADPARAMCOUNT;
        if (args->rgvarg[0].vt != VT_BSTR) return DISP_E_TYPEMISMATCH;
        if (!result) return E_POINTER;
        VariantInit(result);
        try {
            const auto input = args->rgvarg[0].bstrVal;
            const auto output = call(std::wstring(input ? input : L"", SysStringLen(input)));
            result->bstrVal = SysAllocStringLen(output.data(), static_cast<UINT>(output.size()));
            if (!result->bstrVal) return E_OUTOFMEMORY;
            result->vt = VT_BSTR;
            return S_OK;
        } catch (...) { return E_FAIL; }
    }
};
