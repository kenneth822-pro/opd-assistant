package np.opd.assistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes a downsampled bitmap from a file path off the main thread. Kept tiny on purpose —
 * the app ships no image-loading library.
 */
@Composable
fun rememberBitmap(path: String?, maxDim: Int = 1024): Bitmap? {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            if (path.isNullOrBlank()) null else decodeSampled(path, maxDim)
        }
    }
    return bitmap
}

private fun decodeSampled(path: String, maxDim: Int): Bitmap? = try {
    val f = File(path)
    if (!f.exists()) null else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)
    }
} catch (e: Exception) {
    null
}

/** Shows one image scaled to fit, or a placeholder when the file is missing. */
@Composable
fun MediaImage(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bmp = rememberBitmap(path)
    Box(modifier.background(WardPalette.freeBg), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            Text("image", color = WardPalette.freeFg, fontSize = 11.sp)
        }
    }
}

/**
 * Side-by-side comparison of two images (e.g. earliest vs latest wound photo) with a draggable
 * divider. Left of the divider shows [leftPath], right shows [rightPath].
 */
@Composable
fun WoundComparison(
    leftPath: String?,
    rightPath: String?,
    leftCaption: String,
    rightCaption: String,
    modifier: Modifier = Modifier
) {
    val left = rememberBitmap(leftPath)?.asImageBitmap()
    val right = rememberBitmap(rightPath)?.asImageBitmap()
    var fraction by remember { mutableStateOf(0.5f) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(WardPalette.freeBg)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                right?.let {
                    drawImage(
                        image = it,
                        srcSize = androidx.compose.ui.unit.IntSize(it.width, it.height),
                        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt())
                    )
                }
                left?.let {
                    val split = w * fraction
                    clipRect(0f, 0f, split, h) {
                        drawImage(
                            image = it,
                            srcSize = androidx.compose.ui.unit.IntSize(it.width, it.height),
                            dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt())
                        )
                    }
                    drawLine(
                        color = Color.White,
                        start = Offset(split, 0f),
                        end = Offset(split, h),
                        strokeWidth = 4f
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("◀ $leftCaption", fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("$rightCaption ▶", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    }
}
