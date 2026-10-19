package com.marco.magictouchremove

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.pow

/** La tela: foto, maschera rossa, cursore del pennello, lasso e riquadro di selezione. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CanvasView(st: EditorState, modifier: Modifier = Modifier) {
    val img = st.image
    val shown = if (st.comparing) st.original else img
    val bitmap = remember(shown) { shown?.toComposeImageBitmap() }
    val overlay = remember(st.version, img, st.hasMask) { st.buildOverlay() }

    val icon = if (st.tool == Tool.HAND) PointerIcon.Hand else PointerIcon.Crosshair

    Box(
        modifier
            .background(PsCanvas)
            .clipToBounds()
            .onSizeChanged { st.onViewSize(it.width.toFloat(), it.height.toFloat()) }
            .pointerHoverIcon(icon)
            .onPointerEvent(PointerEventType.Move) { st.hover = it.changes.first().position }
            .onPointerEvent(PointerEventType.Exit) { st.hover = null }
            .onPointerEvent(PointerEventType.Scroll) {
                val c = it.changes.first()
                val dy = c.scrollDelta.y
                if (dy != 0f) st.zoomAt(1.12f.pow(-dy), c.position.x, c.position.y)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (st.image == null || st.busy) return@awaitEachGesture
                    val buttons = currentEvent.buttons
                    val t = if (buttons.isTertiaryPressed || buttons.isSecondaryPressed) Tool.HAND else st.tool
                    when (t) {
                        Tool.HAND -> {
                            drag(down.id) { c ->
                                st.pan(c.positionChange())
                                c.consume()
                            }
                        }
                        Tool.BRUSH, Tool.ERASER, Tool.RESTORE -> {
                            st.beginStroke(t)
                            st.strokeTo(st.toImage(down.position))
                            drag(down.id) { c ->
                                st.strokeTo(st.toImage(c.position))
                                c.consume()
                            }
                            st.endStroke()
                        }
                        Tool.LASSO -> {
                            st.lassoPts.clear()
                            st.lassoPts.add(st.toImage(down.position))
                            drag(down.id) { c ->
                                st.lassoPts.add(st.toImage(c.position))
                                c.consume()
                            }
                            st.finishLasso()
                        }
                        Tool.SELECT -> {
                            val start = down.position
                            var cur = start
                            drag(down.id) { c ->
                                cur = c.position
                                st.dragBox = Rect(
                                    min(start.x, cur.x), min(start.y, cur.y),
                                    max(start.x, cur.x), max(start.y, cur.y)
                                )
                                c.consume()
                            }
                            st.dragBox = null
                            st.finishSelect(start, cur, (cur - start).getDistance() > 6f)
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (bitmap != null && img != null) {
                val dstOff = IntOffset(st.panX.roundToInt(), st.panY.roundToInt())
                val dstSize = IntSize(
                    max(1, (img.width * st.zoom).roundToInt()),
                    max(1, (img.height * st.zoom).roundToInt())
                )
                // ombra e bordo del documento
                drawRect(
                    Color(0x66000000),
                    topLeft = Offset(dstOff.x + 3f, dstOff.y + 3f),
                    size = Size(dstSize.width.toFloat(), dstSize.height.toFloat())
                )
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = dstOff,
                    dstSize = dstSize,
                    filterQuality = if (st.zoom >= 2f) FilterQuality.None else FilterQuality.Medium
                )
                if (overlay != null && !st.comparing) {
                    drawImage(
                        image = overlay,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(overlay.width, overlay.height),
                        dstOffset = dstOff,
                        dstSize = dstSize,
                        alpha = st.maskOpacity,
                        filterQuality = FilterQuality.Low
                    )
                }

                // lasso in corso
                if (st.lassoPts.size > 1) {
                    val path = Path()
                    val p0 = st.lassoPts[0]
                    path.moveTo(p0.x * st.zoom + st.panX, p0.y * st.zoom + st.panY)
                    for (i in 1 until st.lassoPts.size) {
                        val p = st.lassoPts[i]
                        path.lineTo(p.x * st.zoom + st.panX, p.y * st.zoom + st.panY)
                    }
                    drawPath(
                        path, Color.White,
                        style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)))
                    )
                }

                // riquadro di selezione in corso
                st.dragBox?.let { r ->
                    drawRect(
                        Color.White, topLeft = r.topLeft, size = r.size,
                        style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)))
                    )
                }

                // cursore del pennello
                val h = st.hover
                if (h != null && !st.busy &&
                    (st.tool == Tool.BRUSH || st.tool == Tool.ERASER || st.tool == Tool.RESTORE)
                ) {
                    val r = st.brushSize / 2f
                    drawCircle(Color.Black, r + 1f, h, style = Stroke(1f))
                    drawCircle(Color.White, r, h, style = Stroke(1f))
                }
            }
        }
    }
}
