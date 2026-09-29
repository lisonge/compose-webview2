package li.songe.compose.webview2.internal

import li.songe.compose.webview2.WebViewViewport
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class BrowserFrame(val sequence: Long, val bitmap: ImageBitmap, val viewport: WebViewViewport)

internal fun decodeFrame(bytes: ByteArray): BrowserFrame {
    require(bytes.size >= 32)
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val width = header.int
    val height = header.int
    val sequence = header.long
    val revision = header.long
    val scale = header.double
    require(width in 1..8192 && height in 1..8192 && bytes.size.toLong() == 32L + width.toLong() * height * 4)
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    // Immutable snapshots: Compose/Skia owns the bitmap until its draw recordings release it.
    val bitmap = Data.makeFromBytes(bytes, 32, bytes.size - 32).use { pixels ->
        Image.makeRaster(info, pixels, width * 4).use { it.toComposeImageBitmap() }
    }
    return BrowserFrame(sequence, bitmap, WebViewViewport(width, height, scale, revision))
}
