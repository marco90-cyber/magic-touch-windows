package com.marco.magictouchremove

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette ispirata al tema scuro di Photoshop
val PsCanvas = Color(0xFF282828)
val PsPanel = Color(0xFF323232)
val PsPanelHeader = Color(0xFF2B2B2B)
val PsBar = Color(0xFF3C3C3C)
val PsBorder = Color(0xFF1A1A1A)
val PsHover = Color(0xFF454545)
val PsSelected = Color(0xFF555555)
val PsText = Color(0xFFD6D6D6)
val PsDim = Color(0xFF9A9A9A)
val PsAccent = Color(0xFF2D8CEB)
val PsError = Color(0xFFE5534B)

/** Cursore a scorrimento compatto, come quelli della barra opzioni di Photoshop. */
@Composable
fun PsSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    valueText: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackWidth: androidx.compose.ui.unit.Dp = 110.dp,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PsDim, fontSize = 12.sp)
        Box(Modifier.width(8.dp))
        var w by remember { mutableIntStateOf(1) }
        val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
        fun set(x: Float) = onChange(min + (x / w).coerceIn(0f, 1f) * (max - min))
        Canvas(
            Modifier
                .width(trackWidth)
                .height(22.dp)
                .onSizeChanged { w = it.width.coerceAtLeast(1) }
                .pointerInput(w) { detectTapGestures { set(it.x) } }
                .pointerInput(w) { detectHorizontalDragGestures(onDragStart = { set(it.x.coerceIn(0f, w.toFloat())) }) { c, _ -> set(c.position.x) } }
        ) {
            val cy = size.height / 2f
            drawLine(Color(0xFF1F1F1F), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 4f, cap = StrokeCap.Round)
            drawLine(PsAccent, Offset(0f, cy), Offset(size.width * frac, cy), strokeWidth = 4f, cap = StrokeCap.Round)
            drawCircle(Color(0xFFEDEDED), 6.5.dp.toPx(), Offset(size.width * frac, cy))
            drawCircle(Color(0xFF1A1A1A), 6.5.dp.toPx(), Offset(size.width * frac, cy), style = Stroke(1f))
        }
        Box(Modifier.width(6.dp))
        Text(valueText, color = PsText, fontSize = 12.sp, modifier = Modifier.width(38.dp))
    }
}

/** Icona dell'app (la stessa dell'app Android), caricata da resources/icon.png. */
val AppIcon: BitmapPainter by lazy { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }
