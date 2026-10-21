package com.marco.magictouchremove

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** Ingrandimento con Real-ESRGAN a tasselli (il modello può avere forma fissa o dinamica, NCHW o NHWC). */
class Upscaler(path: String) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = Gpu.session(env, path)
    private val inName = session.inputNames.first()
    private val shape = (session.inputInfo[inName]!!.info as TensorInfo).shape
    private val nchw = shape.size == 4 && shape[1] == 3L
    private val fixedH = (if (nchw) shape[2] else shape[1]).toInt()
    private val fixedW = (if (nchw) shape[3] else shape[2]).toInt()

    /** @return immagine ingrandita; [progress] riceve 0..1. */
    fun upscale(src: BufferedImage, isCancelled: () -> Boolean, progress: (Float) -> Unit): BufferedImage {
        val w = src.width
        val h = src.height
        val tileH = if (fixedH > 0) fixedH else 128
        val tileW = if (fixedW > 0) fixedW else 128
        val pad = 8
        val stepW = tileW - 2 * pad
        val stepH = tileH - 2 * pad
        require(stepW > 0 && stepH > 0) { "modello non adatto" }
        val px = src.pixels()

        var scale = 0
        var out: BufferedImage? = null
        var outPx: IntArray? = null
        val tilesX = (w + stepW - 1) / stepW
        val tilesY = (h + stepH - 1) / stepH
        var done = 0
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            if (isCancelled()) throw java.util.concurrent.CancellationException("annullato")
            val x0 = tx * stepW
            val y0 = ty * stepH
            val inp = FloatArray(3 * tileW * tileH)
            for (yy in 0 until tileH) {
                val sy = (y0 - pad + yy).coerceIn(0, h - 1)
                for (xx in 0 until tileW) {
                    val sx = (x0 - pad + xx).coerceIn(0, w - 1)
                    val p = px[sy * w + sx]
                    val r = ((p shr 16) and 255) / 255f
                    val g = ((p shr 8) and 255) / 255f
                    val b = (p and 255) / 255f
                    if (nchw) {
                        val i = yy * tileW + xx
                        inp[i] = r; inp[tileW * tileH + i] = g; inp[2 * tileW * tileH + i] = b
                    } else {
                        val i = (yy * tileW + xx) * 3
                        inp[i] = r; inp[i + 1] = g; inp[i + 2] = b
                    }
                }
            }
            val ishape = if (nchw) longArrayOf(1, 3, tileH.toLong(), tileW.toLong()) else longArrayOf(1, tileH.toLong(), tileW.toLong(), 3)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(inp), ishape).use { t ->
                session.run(mapOf(inName to t)).use { res ->
                    val ot = res[0] as OnnxTensor
                    val oshape = (ot.info as TensorInfo).shape
                    val oh = (if (nchw) oshape[2] else oshape[1]).toInt()
                    val ow = (if (nchw) oshape[3] else oshape[2]).toInt()
                    if (scale == 0) {
                        scale = max(1, oh / tileH)
                        val total = w.toLong() * scale * h * scale
                        require(total <= 120_000_000L) { "Immagine troppo grande per l'ingrandimento (${w * scale}×${h * scale} px): riducila prima" }
                        out = BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB)
                        outPx = IntArray(w * scale * h * scale)
                    }
                    val b = ot.floatBuffer
                    val oplane = oh * ow
                    val ow2 = w * scale
                    val cw = min(stepW, w - x0)
                    val ch = min(stepH, h - y0)
                    for (yy in 0 until ch * scale) for (xx in 0 until cw * scale) {
                        val ty2 = pad * scale + yy
                        val tx2 = pad * scale + xx
                        val r: Float; val g: Float; val bl: Float
                        if (nchw) {
                            val i = ty2 * ow + tx2
                            r = b.get(i); g = b.get(oplane + i); bl = b.get(2 * oplane + i)
                        } else {
                            val i = (ty2 * ow + tx2) * 3
                            r = b.get(i); g = b.get(i + 1); bl = b.get(i + 2)
                        }
                        val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bi = (bl * 255f + 0.5f).toInt().coerceIn(0, 255)
                        outPx!![(y0 * scale + yy) * ow2 + x0 * scale + xx] = (255 shl 24) or (ri shl 16) or (gi shl 8) or bi
                    }
                }
            }
            done++
            progress(done / (tilesX * tilesY).toFloat())
        }
        out!!.setRGB(0, 0, w * scale, h * scale, outPx!!, 0, w * scale)
        return out!!
    }

    fun close() { runCatching { session.close() } }
}

/** Rimozione dello sfondo (U²-Net / IS-Net): restituisce l'immagine con il canale alfa. */
class BackgroundRemover(path: String, private val size: Int) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = Gpu.session(env, path)

    fun remove(src: BufferedImage): BufferedImage {
        val w = src.width
        val h = src.height
        val small = scaled(src, size, size).pixels()
        var mx = 1f
        for (p in small) {
            mx = max(mx, ((p shr 16) and 255).toFloat()); mx = max(mx, ((p shr 8) and 255).toFloat()); mx = max(mx, (p and 255).toFloat())
        }
        val mean = if (size == 1024) floatArrayOf(.5f, .5f, .5f) else floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = if (size == 1024) floatArrayOf(1f, 1f, 1f) else floatArrayOf(0.229f, 0.224f, 0.225f)
        val plane = size * size
        val inp = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = small[i]
            inp[i] = (((p shr 16) and 255) / mx - mean[0]) / std[0]
            inp[plane + i] = (((p shr 8) and 255) / mx - mean[1]) / std[1]
            inp[2 * plane + i] = ((p and 255) / mx - mean[2]) / std[2]
        }
        val pred = FloatArray(plane)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(inp), longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { res ->
                val b = (res[0] as OnnxTensor).floatBuffer
                for (i in 0 until plane) pred[i] = b.get(i)
            }
        }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in pred) { lo = min(lo, v); hi = max(hi, v) }
        val range = max(hi - lo, 1e-6f)
        val aPx = IntArray(plane) { ((((pred[it] - lo) / range).coerceIn(0f, 1f) * 255f).toInt() shl 24) or 0xFFFFFF }
        val aFull = scaled(imageOf(size, size, aPx), w, h).pixels()
        val out = src.copyImage()
        val op = out.pixels()
        for (i in op.indices) op[i] = (aFull[i] and 0xFF000000.toInt()) or (op[i] and 0xFFFFFF)
        out.setRGB(0, 0, w, h, op, 0, w)
        return out
    }

    fun close() { runCatching { session.close() } }
}
