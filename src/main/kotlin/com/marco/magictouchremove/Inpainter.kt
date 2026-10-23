package com.marco.magictouchremove

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Rimozione oggetti con LaMa / MI-GAN (ONNX Runtime).
 *
 * Il modello lavora a 512x512: per la massima precisione si ritaglia solo la zona attorno alla
 * maschera (con contesto), si ricostruisce lì e si reincolla nell'immagine originale a piena
 * risoluzione, sostituendo SOLO i pixel mascherati (bordo sfumato).
 */
abstract class Inpainter {
    protected val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** Ricostruisce un quadrato n×n: [px] pixel ARGB, [hole] true dove ricostruire. Ritorna i pixel risultanti. */
    protected abstract fun run512(px: IntArray, hole: BooleanArray, n: Int): IntArray
    abstract fun close()

    /** @param mask w*h byte, >127 = pixel da rimuovere. Ritorna null se la maschera è vuota. */
    fun inpaint(
        image: BufferedImage, mask: ByteArray, expand: Int, feather: Int,
        detailBoost: ((BufferedImage) -> BufferedImage?)? = null,
    ): BufferedImage? {
        val w = image.width
        val h = image.height

        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((mask[row + x].toInt() and 255) > 127) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null

        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val side0 = (max(bw, bh) * 1.8f + 64f).toInt()
        val left: Int
        val top: Int
        val cw: Int
        val ch: Int
        if (side0 <= min(w, h)) {
            cw = side0; ch = side0
            left = ((minX + maxX) / 2 - side0 / 2).coerceIn(0, w - side0)
            top = ((minY + maxY) / 2 - side0 / 2).coerceIn(0, h - side0)
        } else { // maschera enorme: si usa l'intera immagine
            left = 0; top = 0; cw = w; ch = h
        }

        val n = SIZE
        // maschera a 512x512 (max-pooling per non perdere i tratti sottili)
        var m512 = BooleanArray(n * n)
        for (my in 0 until n) {
            val y0 = my * ch / n
            val y1 = max(y0 + 1, (my + 1) * ch / n)
            for (mx in 0 until n) {
                val x0 = mx * cw / n
                val x1 = max(x0 + 1, (mx + 1) * cw / n)
                m512[my * n + mx] = anyMasked(mask, w, left, top, x0, x1, y0, y1)
            }
        }
        m512 = dilate(m512, n, expand)

        val crop = image.crop(left, top, cw, ch)
        val in512 = scaled(crop, n, n)
        val ipx = in512.pixels()

        val plane = n * n
        val outPx = run512(ipx, m512, n)

        // risultato e maschera morbida riportati alla risoluzione del ritaglio
        val outBmp512 = imageOf(n, n, outPx)
        // facoltativo: Real-ESRGAN recupera dettagli sulla parte ricostruita (altrimenti resta morbida)
        val boosted = try {
            detailBoost?.invoke(outBmp512)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.w("Miglioramento dei dettagli non riuscito (${e.javaClass.simpleName}: ${e.message}): uso il risultato normale")
            null
        }
        val outBmp = when {
            boosted != null -> scaled(boosted, cw, ch)
            cw == n && ch == n -> outBmp512
            else -> scaled(outBmp512, cw, ch)
        }

        val soft = softAlpha(m512, n, feather)
        val aPx = IntArray(plane) { ((soft[it] * 255f).toInt() shl 24) or 0xFFFFFF }
        val aBmp512 = imageOf(n, n, aPx)
        val aBmp = if (cw == n && ch == n) aBmp512 else scaled(aBmp512, cw, ch)

        val cp = crop.pixels()
        val op = outBmp.pixels()
        val ap = aBmp.pixels()
        matchTexture(cp, op, ap, cw, ch, TextureMatch.grain, TextureMatch.sharp)

        for (i in cp.indices) {
            val a = (ap[i] ushr 24) / 255f
            if (a <= 0f) continue
            val o = op[i]
            val c = cp[i]
            val r = (((o shr 16) and 255) * a + ((c shr 16) and 255) * (1 - a)).toInt()
            val g = (((o shr 8) and 255) * a + ((c shr 8) and 255) * (1 - a)).toInt()
            val b = ((o and 255) * a + (c and 255) * (1 - a)).toInt()
            cp[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = image.copyImage()
        result.setRGB(left, top, cw, ch, cp, 0, cw)
        return result
    }

    private fun luma(p: Int): Float = 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)

    /** Stima robusta (mediana) della grana: deviazione standard del rumore ad alta frequenza. */
    private fun grainSigma(px: IntArray, w: Int, h: Int, pick: (Int) -> Boolean): Float {
        val hist = IntArray(1024)
        var count = 0
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val i = y * w + x
                if (pick(i)) {
                    val hp = luma(px[i]) - (luma(px[i - 1]) + luma(px[i + 1]) + luma(px[i - w]) + luma(px[i + w])) / 4f
                    hist[min(1023, (kotlin.math.abs(hp) * 8f).toInt())]++
                    count++
                }
                x += 2
            }
            y += 2
        }
        if (count < 50) return -1f
        var acc = 0
        for (b in 0 until 1024) {
            acc += hist[b]
            if (acc >= count / 2) return ((b + 0.5f) / 8f) / 0.6745f / 1.118f
        }
        return -1f
    }

    /**
     * Il riempimento dell'IA è più liscio della foto: gli si aggiunge la stessa grana misurata attorno alla selezione
     * e un po' di nitidezza, così non si nota la differenza.
     */
    private fun matchTexture(cp: IntArray, op: IntArray, ap: IntArray, w: Int, h: Int, grain: Float, sharp: Float) {
        if (grain <= 0f && sharp <= 0f) return
        // nitidezza (unsharp con sfocatura 3x3) solo dentro il riempimento
        if (sharp > 0f) {
            val src = op.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if ((ap[i] ushr 24) == 0) continue
                var r = 0; var g = 0; var b = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val p = src[i + dy * w + dx]
                    r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                }
                val c = src[i]
                val nr = (((c shr 16) and 255) + sharp * (((c shr 16) and 255) - r / 9f)).toInt().coerceIn(0, 255)
                val ng = (((c shr 8) and 255) + sharp * (((c shr 8) and 255) - g / 9f)).toInt().coerceIn(0, 255)
                val nb = ((c and 255) + sharp * ((c and 255) - b / 9f)).toInt().coerceIn(0, 255)
                op[i] = (255 shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        if (grain <= 0f) return
        val target = grainSigma(cp, w, h) { (ap[it] ushr 24) == 0 }
        val fill = grainSigma(op, w, h) { (ap[it] ushr 24) > 200 }
        if (target < 0f || fill < 0f) return
        val add = kotlin.math.sqrt(max(0f, target * target - fill * fill)) * grain
        if (add < 0.3f) return
        val rnd = java.util.Random(12345)
        for (i in op.indices) {
            val a = (ap[i] ushr 24) / 255f
            if (a <= 0f) continue
            val n = rnd.nextGaussian().toFloat() * add * a
            val c = op[i]
            val r = (((c shr 16) and 255) + n + rnd.nextGaussian().toFloat() * add * 0.25f * a).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 255) + n + rnd.nextGaussian().toFloat() * add * 0.25f * a).toInt().coerceIn(0, 255)
            val b = ((c and 255) + n + rnd.nextGaussian().toFloat() * add * 0.25f * a).toInt().coerceIn(0, 255)
            op[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        AppLog.i("Grana abbinata: foto %.1f, riempimento %.1f, aggiunta %.1f".format(target, fill, add))
    }

    private fun anyMasked(mask: ByteArray, w: Int, left: Int, top: Int, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        for (yy in y0 until y1) {
            val row = (top + yy) * w + left
            for (xx in x0 until x1) if ((mask[row + xx].toInt() and 255) > 127) return true
        }
        return false
    }

    /** Opacità del riempimento: 1 dentro la maschera, con bordo che sfuma verso l'esterno per [r] pixel. */
    private fun softAlpha(m: BooleanArray, n: Int, r: Int): FloatArray {
        val a = FloatArray(n * n) { if (m[it]) 1f else 0f }
        if (r <= 0) return a
        val tmp = FloatArray(n * n)
        val k = 2 * r + 1
        for (y in 0 until n) {
            var acc = 0f
            for (x in -r..r) acc += a[y * n + x.coerceIn(0, n - 1)]
            for (x in 0 until n) {
                tmp[y * n + x] = acc / k
                acc += a[y * n + (x + r + 1).coerceIn(0, n - 1)] - a[y * n + (x - r).coerceIn(0, n - 1)]
            }
        }
        val out = FloatArray(n * n)
        for (x in 0 until n) {
            var acc = 0f
            for (y in -r..r) acc += tmp[y.coerceIn(0, n - 1) * n + x]
            for (y in 0 until n) {
                out[y * n + x] = minOf(1f, acc / k * 2f)
                acc += tmp[(y + r + 1).coerceIn(0, n - 1) * n + x] - tmp[(y - r).coerceIn(0, n - 1) * n + x]
            }
        }
        for (j in out.indices) if (m[j]) out[j] = 1f
        return out
    }

    private fun dilate(m: BooleanArray, n: Int, r: Int): BooleanArray {
        if (r <= 0) return m
        val tmp = BooleanArray(n * n)
        val out = BooleanArray(n * n)
        for (y in 0 until n) for (x in 0 until n) if (m[y * n + x]) {
            for (k in max(0, x - r)..min(n - 1, x + r)) tmp[y * n + k] = true
        }
        for (y in 0 until n) for (x in 0 until n) if (tmp[y * n + x]) {
            for (k in max(0, y - r)..min(n - 1, y + r)) out[k * n + x] = true
        }
        return out
    }

    companion object {
        const val SIZE = 512
    }
}

private fun newSession(env: OrtEnvironment, path: String): OrtSession {
    return Gpu.session(env, path)
}

/** LaMa (big-lama): immagine float 0..1, maschera 1 = da ricostruire. */
class LamaInpainter(path: String) : Inpainter() {
    private val session = newSession(env, path)
    private val imageInput: String
    private val maskInput: String

    init {
        val names = session.inputNames.toList()
        imageInput = names.firstOrNull { it.contains("image", true) } ?: names[0]
        maskInput = names.firstOrNull { it.contains("mask", true) } ?: names[1]
    }

    override fun run512(px: IntArray, hole: BooleanArray, n: Int): IntArray {
        val plane = n * n
        val imgArr = FloatArray(3 * plane)
        val maskArr = FloatArray(plane)
        for (i in 0 until plane) {
            val p = px[i]
            imgArr[i] = ((p shr 16) and 255) / 255f
            imgArr[plane + i] = ((p shr 8) and 255) / 255f
            imgArr[2 * plane + i] = (p and 255) / 255f
            maskArr[i] = if (hole[i]) 1f else 0f
        }
        val outPx = IntArray(plane)
        val shapeImg = longArrayOf(1, 3, n.toLong(), n.toLong())
        val shapeMask = longArrayOf(1, 1, n.toLong(), n.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(imgArr), shapeImg).use { ti ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(maskArr), shapeMask).use { tm ->
                session.run(mapOf(imageInput to ti, maskInput to tm)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = res[0].value as Array<Array<Array<FloatArray>>>
                    var mx = 0f
                    for (c in 0 until 3) for (y in 0 until n) for (x in 0 until n) mx = max(mx, out[0][c][y][x])
                    val k = if (mx <= 2f) 255f else 1f
                    for (y in 0 until n) for (x in 0 until n) {
                        val r = (out[0][0][y][x] * k).toInt().coerceIn(0, 255)
                        val g = (out[0][1][y][x] * k).toInt().coerceIn(0, 255)
                        val b = (out[0][2][y][x] * k).toInt().coerceIn(0, 255)
                        outPx[y * n + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
        return outPx
    }

    override fun close() {
        runCatching { session.close() }
    }
}

/** MI-GAN (pipeline ONNX): immagine uint8 0..255, maschera uint8 con 255 = da mantenere e 0 = da ricostruire. */
class MiganInpainter(path: String) : Inpainter() {
    private val session = newSession(env, path)

    override fun run512(px: IntArray, hole: BooleanArray, n: Int): IntArray {
        val plane = n * n
        val img = ByteBuffer.allocateDirect(3 * plane)
        val msk = ByteBuffer.allocateDirect(plane)
        for (i in 0 until plane) img.put(i, ((px[i] shr 16) and 255).toByte())
        for (i in 0 until plane) img.put(plane + i, ((px[i] shr 8) and 255).toByte())
        for (i in 0 until plane) img.put(2 * plane + i, (px[i] and 255).toByte())
        for (i in 0 until plane) msk.put(i, if (hole[i]) 0.toByte() else 255.toByte())
        val outPx = IntArray(plane)
        val n8 = n.toLong()
        OnnxTensor.createTensor(env, img, longArrayOf(1, 3, n8, n8), OnnxJavaType.UINT8).use { ti ->
            OnnxTensor.createTensor(env, msk, longArrayOf(1, 1, n8, n8), OnnxJavaType.UINT8).use { tm ->
                session.run(mapOf("image" to ti, "mask" to tm)).use { res ->
                    val ob = (res[0] as OnnxTensor).byteBuffer
                    for (i in 0 until plane) {
                        val r = ob.get(i).toInt() and 255
                        val g = ob.get(plane + i).toInt() and 255
                        val b = ob.get(2 * plane + i).toInt() and 255
                        outPx[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
        return outPx
    }

    override fun close() {
        runCatching { session.close() }
    }
}
