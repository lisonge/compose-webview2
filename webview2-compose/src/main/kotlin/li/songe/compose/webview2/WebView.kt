package li.songe.compose.webview2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.isActive
import li.songe.compose.webview2.internal.NativeBridge
import li.songe.compose.webview2.internal.decodeFrame
import li.songe.compose.webview2.internal.JavascriptBridgeRuntime
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import kotlin.math.roundToInt

/**
 * Captured WebView2 frames painted in the normal Compose scene; no visible native surface.
 * Setting [running] to false releases the browser asynchronously but retains its last image
 * until this composable leaves the scene. Setting it back to true creates a new browser.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun WebView(state: WebViewState, modifier: Modifier = Modifier, settings: WebViewSettings = WebViewSettings(), running: Boolean = true, bindings: WebViewBindings? = null, client: WebViewClient? = null) {
    val window = LocalAwtWindow.current
    val density = LocalDensity.current.density
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val allowNativeFocus by rememberUpdatedState(settings.requestNativeFocus)
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var session by remember { mutableLongStateOf(0) }

    val clientRuntime = remember { li.songe.compose.webview2.internal.WebViewClientRuntime(client) }
    SideEffect { clientRuntime.client = client }
    DisposableEffect(clientRuntime) { onDispose { clientRuntime.close() } }
    DisposableEffect(window, state, settings.userDataDirectory, running, bindings) {
        check(state.session == 0L) { "One WebViewState cannot be attached to multiple WebViews" }
        var id = 0L
        val bridge = bindings?.let(::JavascriptBridgeRuntime)
        if (running) try {
            checkNotNull(window) { "WebView requires an AWT window" }
            File(settings.userDataDirectory).mkdirs()
            state.attached()
            val parent = NativeBridge.windowHandle(window)
            id = if (bridge == null) NativeBridge.create(parent, settings.userDataDirectory, settings.preferredColorScheme.ordinal)
                else NativeBridge.createBound(parent, settings.userDataDirectory, bridge.nativeCallbacks(), bridge.initializationScript, settings.preferredColorScheme.ordinal)
            NativeBridge.setClient(id, clientRuntime.callback)
            NativeBridge.configure(id, settings)
            bridge?.nativeId = id
            session = id
            state.session = id
            state.updatePage(arrayOf("", "", "false", "false", "false"))
            state.status = "Initializing WebView2"
            state.requestedViewport = null; state.frameViewport = null
            state.frameCount = 0
            image = null
        } catch (error: Exception) { state.initializationFailed(error.message ?: error.toString(), id == 0L); if (id != 0L) state.closing(id) }
        catch (error: LinkageError) { state.initializationFailed("Loading native DLL: ${error.message}", id == 0L); if (id != 0L) state.closing(id) }
        else if (state.lifecycle == WebViewLifecycle.Detached) { state.lifecycle = WebViewLifecycle.Closed; state.status = "Closed" }
        onDispose {
            bridge?.close()
            if (id != 0L) state.closing(id)
            state.session = 0
            session = 0
            state.isLoading = false
            state.canGoBack = false
            state.canGoForward = false
            // The captured bitmap belongs to Compose, not the native session.
            // Preserve it while the parent window is waiting to close.
        }
    }
    LaunchedEffect(session, settings.preferredColorScheme) {
        if (session != 0L) NativeBridge.colorScheme(session, settings.preferredColorScheme.ordinal)
    }
    LaunchedEffect(session, settings.userAgent, settings.javaScriptEnabled, settings.zoomFactor,
        settings.userZoomEnabled, settings.devToolsEnabled, settings.contextMenuEnabled, settings.statusBarEnabled) {
        if (session != 0L) NativeBridge.configure(session, settings)
    }
    LaunchedEffect(session, state.navigationRevision) {
        if (session != 0L) NativeBridge.navigate(session, state.content, state.html)
    }
    LaunchedEffect(session, size, origin, density) {
        if (session != 0L && size.width > 0 && size.height > 0) {
            val old = state.requestedViewport
            val changed = old == null || old.width != size.width || old.height != size.height || old.scale != density.toDouble()
            // Explicit revisions prevent metadata from being inferred from asynchronous native commands.
            val revision = if (old == null) 1L
                else old.revision + if (changed) 1 else 0
            state.requestedViewport = WebViewViewport(size.width, size.height, density.toDouble(), revision)
            NativeBridge.resize(session, origin.x.roundToInt(), origin.y.roundToInt(), size.width, size.height, density.toDouble(), revision)
        }
    }
    LaunchedEffect(session) {
        val id = session
        if (id == 0L) return@LaunchedEffect
        var sequence = 0L
        while (isActive) {
            withFrameNanos { }
            state.status = NativeBridge.status(id)
            state.updatePage(NativeBridge.pageState(id))
            state.updateError(NativeBridge.errorState(id))
            val focusMove = NativeBridge.takeFocusMove(id)
            if (focusMove != 0 && allowNativeFocus) {
                window?.mostRecentFocusOwner?.requestFocusInWindow()
                focusManager.moveFocus(if (focusMove < 0) FocusDirection.Previous else FocusDirection.Next)
            }
            NativeBridge.frame(id, sequence)?.let { bytes ->
                val frame = decodeFrame(bytes)
                sequence = frame.sequence
                if (frame.viewport != state.requestedViewport) return@let
                image = frame.bitmap
                state.frameCount++
                state.frameWidth = frame.bitmap.width
                state.frameHeight = frame.bitmap.height
                state.frameViewport = frame.viewport
            }
        }
    }
    Box(modifier.clipToBounds().onGloballyPositioned {
        size = it.size
        origin = it.positionInWindow()
    }.focusRequester(focus).onFocusChanged {
        if (session != 0L && settings.requestNativeFocus) NativeBridge.focus(session, it.isFocused)
    }.focusable().pointerInput(session, settings.requestNativeFocus) {
        val id = session
        if (id == 0L) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.any { it.isConsumed }) continue
                val point = event.changes.firstOrNull()?.position ?: continue
                val awt = event.nativeEvent as? MouseEvent
                val button = awt?.button ?: MouseEvent.BUTTON1
                var keys = 0
                if (event.buttons.isPrimaryPressed) keys = keys or 1
                if (event.buttons.isSecondaryPressed) keys = keys or 2
                if (event.keyboardModifiers.isShiftPressed) keys = keys or 4
                if (event.keyboardModifiers.isCtrlPressed) keys = keys or 8
                if (event.buttons.isTertiaryPressed) keys = keys or 16
                val message = when (event.type) {
                    PointerEventType.Press -> when (button) { 3 -> 0x204; 2 -> 0x207; else -> if ((awt?.clickCount ?: 1) >= 2) 0x203 else 0x201 }
                    PointerEventType.Release -> when (button) { 3 -> 0x205; 2 -> 0x208; else -> 0x202 }
                    PointerEventType.Scroll -> if (event.changes.first().scrollDelta.x != 0f) 0x20E else 0x20A
                    PointerEventType.Exit -> 0x2A3
                    else -> 0x200
                }
                if (event.type == PointerEventType.Press && settings.requestNativeFocus) {
                    focus.requestFocus()
                    NativeBridge.focus(id, true)
                }
                val delta = if (message == 0x20E) (event.changes.first().scrollDelta.x * 120).roundToInt()
                    else if (awt is MouseWheelEvent) (-awt.preciseWheelRotation * 120).roundToInt()
                    else (-event.changes.first().scrollDelta.y * 120).roundToInt()
                NativeBridge.mouse(id, message, keys, if (event.type == PointerEventType.Scroll) delta else 0,
                    point.x.roundToInt(), point.y.roundToInt())
                event.changes.forEach { it.consume() }
            }
        }
    }) {
        Canvas(Modifier.matchParentSize()) {
            image?.let { drawImage(it, dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())) }
        }
    }
}
