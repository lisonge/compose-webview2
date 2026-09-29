# 后台性能基线

在 sample 的 `--test` 模式运行，不激活窗口：

```powershell
.\gradlew.bat :webview2-sample:run --args="--test"
.\scripts\measure-performance.ps1 -Seconds 5 -Name optimized
```

结果写入 `build/performance/<Name>.json`。脚本使用本地 HTTP API，覆盖静态页面、CSS 动画、脚本滚动、连续调整窗口尺寸；结束后恢复原 URL。滚动由脚本驱动，不代表真实输入验收。

## 2026-09-29 对比

同机、相同交付尺寸 983×363、scale=1.25，每种场景约 5 秒。baseline 使用 `copyOfRange`，optimized 改为 `Data.makeFromBytes(bytes, offset, length)`，避免额外 JVM 像素数组。原始结果在本地 `build/performance/baseline.json` 和 `optimized.json`，不提交构建产物。

| 场景 | 交付 FPS 前 → 后 | JVM 分配 MiB/s 前 → 后 | JVM CPU 核当量前 → 后 |
|---|---:|---:|---:|
| 静态 | 0 → 0 | 0.82 → 0.81 | 0.288 → 0.247 |
| CSS 动画 | 54.60 → 54.93 | 149.60 → 75.73 | 0.468 → 0.505 |
| 脚本滚动 | 55.02 → 54.38 | 150.75 → 74.98 | 0.497 → 0.323 |
| 连续 resize | 6.77 → 6.72 | 19.06 → 10.94 | 0.662 → 0.627 |

动画/滚动的 JVM 分配约减少 49%～50%，帧率基本相同；短时间 CPU 数据有波动，不能据此承诺 CPU 一定降低。静态页面没有新帧时无需重复创建图片。Resize 的 HTTP 观测恢复均值为 142.39 → 143.55 ms，期间实际尺寸变化，FPS 不与稳态动画直接比较。

额外 2×、1576×1154 动画观察：分配 757.66 → 382.55 MiB/s，FPS 54.54 → 55.01。该次 resize 后离屏 AWT 的实际 density 发生变化，因此固定视口比较采用上表；不将离屏 density 变化视为真实跨屏 DPI 验收。

## 测量边界与后续工作

- FPS 是 Compose 收到的帧数，不是显示器呈现帧率。帧只保留最新一份，丢弃过时 revision。
- CPU 为 JVM 进程核当量，包含 JNI 工作，不包含 WebView2 子进程或 GPU；分配为 JVM 所有线程累计字节，不包含 Skia/原生内存。
- BGRA 吞吐量由帧率和尺寸估算，不是总内存拷贝量；resize 使用最终尺寸，不能作为精确流量。
- Resize 时间包含 HTTP 往返与 100 ms 轮询，不是精确的 GPU 合成延迟。
- 所有测量 `activations=0`。真实键盘、IME、跨屏 DPI、最小化恢复及长期资源趋势仍待单独验收。

当前仍有 GPU 回读、原生/JNI 复制和 Skia 上传。下一步如仍有性能瓶颈，应针对真实业务页面采样，再选择状态推送、捕获节流或共享纹理路径；现阶段不引入 GPU 互操作和全局渲染后端变更。
