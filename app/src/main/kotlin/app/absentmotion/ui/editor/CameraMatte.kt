package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// A UI overlay only: neither cached image data nor exported frames contain it.
@Composable
internal fun CameraMatte(x: Float, y: Float, width: Float, height: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (!listOf(x, y, width, height).all { it.isFinite() } || width <= 0 || height <= 0) return@Canvas
        val left = x.coerceIn(0f, size.width)
        val right = (x + width).coerceIn(left, size.width)
        val top = y.coerceIn(0f, size.height)
        val bottom = (y + height).coerceIn(top, size.height)
        val shade = Color(0x880C0E18)
        drawRect(shade, Offset.Zero, Size(size.width, top))
        drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(shade, Offset(0f, top), Size(left, bottom - top))
        drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
        drawRect(Color.White.copy(alpha = 0.65f), Offset(x, y), Size(width, height), style = Stroke(1.dp.toPx()))
    }
}
