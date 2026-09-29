package li.songe.compose.webview2.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import li.songe.compose.webview2.*
import java.io.File

@Composable
internal fun CustomLinkPage(
    state: WebViewState,
    model: DemoModel,
    runtimeAvailable: Boolean,
    profile: File,
    testing: Boolean,
    modifier: Modifier = Modifier,
) {
    val canLoad = runtimeAvailable && !model.closing && model.customUrl.isNotBlank()
    val load = { if (canLoad) state.loadUrl(model.customUrl.trim()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = model.customUrl, onValueChange = { model.customUrl = it },
                modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyUp) { load(); true } else false
                },
                label = { Text("自定义链接（完整 URL）") },
                placeholder = { Text("https://example.com") }, singleLine = true,
                enabled = !model.closing,
            )
            Button(onClick = load, enabled = canLoad) { Text("加载") }
            OutlinedButton(onClick = { state.reload() }, enabled = runtimeAvailable && !model.closing) { Text("刷新") }
            OutlinedButton(onClick = { state.stopLoading() }, enabled = state.isLoading && !model.closing) { Text("停止") }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (runtimeAvailable) {
                    WebView(state, Modifier.fillMaxSize(),
                        WebViewSettings(profile.absolutePath, requestNativeFocus = !testing), running = !model.closing)
                } else Text("未检测到已安装的WebView2", Modifier.align(Alignment.Center))
            }
            SelectionContainer(Modifier.width(280.dp).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("页面状态", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.isLoading) "正在加载…" else "状态：${state.status}")
                    Text("标题：${state.title.ifEmpty { "—" }}")
                    Text("当前链接：${state.url.ifEmpty { "—" }}")
                    Text("错误详情", style = MaterialTheme.typography.titleMedium)
                    val error = state.error
                    if (error == null) Text("暂无 WebView 错误") else {
                        Text("阶段：${error.stage}\n${error.message}", color = MaterialTheme.colorScheme.error)
                        error.url?.let { Text("错误链接：$it") }
                        error.hresult?.let { Text("HRESULT：$it (0x${java.lang.Long.toHexString(it)})") }
                        error.navigationErrorCode?.let { Text("导航错误码：$it") }
                        error.processFailureKind?.let { Text("进程故障类型：$it") }
                    }
                    Text("HTTP 404/500 等响应可能正常显示网站错误页，不一定产生 WebView 导航错误。")
                }
            }
        }
    }
}
