include(FetchContent)
set(WEBVIEW2_SDK_VERSION "1.0.3650.58")
# Update the checksum together with the SDK version.
set(WEBVIEW2_SDK_SHA256 "911a472128c82ac8baa0c486c23342cc9dd6e7dc50d754e676726642ca065c60")

FetchContent_Declare(webview2_sdk
    URL "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/${WEBVIEW2_SDK_VERSION}/microsoft.web.webview2.${WEBVIEW2_SDK_VERSION}.nupkg"
    URL_HASH "SHA256=${WEBVIEW2_SDK_SHA256}"
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(webview2_sdk)
add_library(webview2_loader STATIC IMPORTED)
set_target_properties(webview2_loader PROPERTIES
    IMPORTED_LOCATION "${webview2_sdk_SOURCE_DIR}/build/native/x64/WebView2LoaderStatic.lib"
    INTERFACE_INCLUDE_DIRECTORIES "${webview2_sdk_SOURCE_DIR}/build/native/include")
