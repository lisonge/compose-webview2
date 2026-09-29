# Project documentation

For library usage, see the [README](../README.md).

- [Development setup and module structure](development.md)
- [Architecture and rendering pipeline](architecture.md)
- [JavaScript bridge contract](javascript-bridge.md)
- [Publishing to Maven Local](publishing.md)
- [Shrinking and obfuscation](shrinking.md)
- [Background testing](background-testing.md)
- [Performance baseline](performance.md)
- [Specification](SPEC.md)
- [Implementation plan](PLAN.md)
- [Validation results](validation.md)

## Current status

This is a working CPU-capture prototype, not yet production-ready. The API is experimental. WebView2 renders into a Windows Composition visual; Windows Graphics Capture supplies BGRA frames to the normal Compose scene without a visible native browser surface or a global Skiko backend change.

Ordinary Compose overlays, Popup, DropdownMenu and Dialog have passed background pointer tests. Real keyboard/Chinese IME/focus and Tab traversal still require interactive acceptance. Cross-monitor DPI, minimize/restore, protected video, accessibility, drag/drop, browser-owned popups and GPU recovery are not validated. CPU readback, copying and upload add measurable overhead; the implementation is not zero-copy.

## Licensing

Licensed under the [Apache License 2.0](../LICENSE).
