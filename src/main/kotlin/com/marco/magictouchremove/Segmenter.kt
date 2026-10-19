package com.marco.magictouchremove

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Selezione automatica degli oggetti con MobileSAM / SAM 2 (Segment Anything, ONNX).
 * L'encoder analizza la foto una sola volta; il decoder, rapidissimo, produce la maschera
 * a partire da un clic o da un riquadro.
 */
abstract class Segmenter {
    abstract fun ensureEncoded(img: BufferedImage)
    abstract fun select(img: BufferedImage, point: PointF?, box: RectF?): BooleanArray?
    abstract fun close()
}

private const val TARGET = 1024

private fun sessionOptions(): OrtSession.SessionOptions {
    val o = OrtSession.SessionOptions()
    o.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 16))
    return o
}

class MobileSamSegmenter(encoderPath: String, decoderPath: String) : Segmenter() {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession = env.createSession(encoderPath, sessionOptions())
    private val decoder: OrtSession = env.createSession(decoderPath, sessionOptions())
    private val encoderInput: String = encoder.inputNames.first()

    private var embeddedFor: BufferedImage? = null
    private var embedding: FloatArray? = null

    /** Analizza la foto (una volta sola per immagine). L'encoder vuole la foto con il lato lungo = 1024. */
    @Synchronized
    override fun ensureEncoded(img: BufferedImage) {
        if (embeddedFor === img && embedding != null) return
        val w = img.width
        val h = img.height
        val s = TARGET.toFloat() / max(w, h)
        val rw = (w * s).roundToInt().coerceAtLeast(1)
        val rh = (h * s).roundToInt().coerceAtLeast(1)
        val px = scaled(img, rw, rh).pixels()
        val arr = FloatArray(rw * rh * 3)
        for (i in px.indices) {
            val p = px[i]
            arr[3 * i] = ((p shr 16) and 255).toFloat()
            arr[3 * i + 1] = ((p shr 8) and 255).toFloat()
            arr[3 * i + 2] = (p and 255).toFloat()
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), longArrayOf(rh.toLong(), rw.toLong(), 3)).use { t ->
            encoder.run(mapOf(encoderInput to t)).use { res ->
                val fb = (res[0] as OnnxTensor).floatBuffer
                val e = FloatArray(fb.remaining())
                fb.get(e)
                embedding = e
            }
        }
        embeddedFor = img
    }

    /**
     * @return maschera booleana w*h dell'oggetto, oppure null se non trovato.
     * Un clic ([point]) oppure un riquadro ([box]), in coordinate dell'immagine.
     */
    @Synchronized
    override fun select(img: BufferedImage, point: PointF?, box: RectF?): BooleanArray? {
        ensureEncoded(img)
        val emb = embedding ?: return null
        val w = img.width
        val h = img.height
        val s = TARGET.toFloat() / max(w, h)

        val coords: FloatArray
        val labels: FloatArray
        if (box != null) {
            coords = floatArrayOf(box.left * s, box.top * s, box.right * s, box.bottom * s)
            labels = floatArrayOf(2f, 3f)
        } else if (point != null) {
            coords = floatArrayOf(point.x * s, point.y * s, 0f, 0f) // secondo punto = riempimento
            labels = floatArrayOf(1f, -1f)
        } else return null
        val n = labels.size.toLong()

        return OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 256, 64, 64)).use { te ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, n, 2)).use { tc ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, n)).use { tl ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256)).use { tm ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)).use { th ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(h.toFloat(), w.toFloat())), longArrayOf(2)).use { to ->
                                val inputs = mapOf(
                                    "image_embeddings" to te, "point_coords" to tc, "point_labels" to tl,
                                    "mask_input" to tm, "has_mask_input" to th, "orig_im_size" to to
                                )
                                decoder.run(inputs).use { res ->
                                    val masks = (res[0] as OnnxTensor).floatBuffer
                                    val iou = (res[1] as OnnxTensor).floatBuffer
                                    val count = iou.remaining()
                                    // riquadro: maschera 0; singolo clic: la migliore tra le maschere 1..n
                                    var idx = 0
                                    if (box == null && count > 1) {
                                        var best = -1f
                                        for (i in 1 until count) if (iou.get(i) > best) { best = iou.get(i); idx = i }
                                    }
                                    val off = idx * w * h
                                    val out = BooleanArray(w * h)
                                    var any = false
                                    for (i in out.indices) if (masks.get(off + i) > 0f) { out[i] = true; any = true }
                                    if (any) out else null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        embedding = null
        embeddedFor = null
    }
}

/** SAM 2 Tiny (Meta): foto 1024×1024 normalizzata; le maschere escono a 256×256 e vengono ingrandite in modo morbido. */
class Sam2Segmenter(encoderPath: String, decoderPath: String) : Segmenter() {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession = env.createSession(encoderPath, sessionOptions())
    private val decoder: OrtSession = env.createSession(decoderPath, sessionOptions())
    private var embeddedFor: BufferedImage? = null
    private var embed: FloatArray? = null
    private var feat0: FloatArray? = null
    private var feat1: FloatArray? = null

    @Synchronized
    override fun ensureEncoded(img: BufferedImage) {
        if (embeddedFor === img && embed != null) return
        val px = scaled(img, TARGET, TARGET).pixels()
        val plane = TARGET * TARGET
        val arr = FloatArray(3 * plane)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (i in 0 until plane) {
            val p = px[i]
            arr[i] = (((p shr 16) and 255) / 255f - mean[0]) / std[0]
            arr[plane + i] = (((p shr 8) and 255) / 255f - mean[1]) / std[1]
            arr[2 * plane + i] = ((p and 255) / 255f - mean[2]) / std[2]
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), longArrayOf(1, 3, TARGET.toLong(), TARGET.toLong())).use { t ->
            encoder.run(mapOf(encoder.inputNames.first() to t)).use { res ->
                fun grab(name: String): FloatArray {
                    val fb = (res.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(fb.remaining()).also { fb.get(it) }
                }
                feat0 = grab("high_res_feats_0")
                feat1 = grab("high_res_feats_1")
                embed = grab("image_embed")
            }
        }
        embeddedFor = img
    }

    @Synchronized
    override fun select(img: BufferedImage, point: PointF?, box: RectF?): BooleanArray? {
        ensureEncoded(img)
        val e = embed ?: return null
        val f0 = feat0 ?: return null
        val f1 = feat1 ?: return null
        val w = img.width
        val h = img.height
        val sx = TARGET.toFloat() / w
        val sy = TARGET.toFloat() / h
        val coords: FloatArray
        val labels: FloatArray
        if (box != null) {
            coords = floatArrayOf(box.left * sx, box.top * sy, box.right * sx, box.bottom * sy)
            labels = floatArrayOf(2f, 3f)
        } else if (point != null) {
            coords = floatArrayOf(point.x * sx, point.y * sy)
            labels = floatArrayOf(1f)
        } else return null
        val n = labels.size.toLong()

        return OnnxTensor.createTensor(env, FloatBuffer.wrap(e), longArrayOf(1, 256, 64, 64)).use { te ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(f0), longArrayOf(1, 32, 256, 256)).use { t0 ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(f1), longArrayOf(1, 64, 128, 128)).use { t1 ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, n, 2)).use { tc ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, n)).use { tl ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256)).use { tm ->
                                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)).use { th ->
                                    val inputs = mapOf(
                                        "image_embed" to te, "high_res_feats_0" to t0, "high_res_feats_1" to t1,
                                        "point_coords" to tc, "point_labels" to tl, "mask_input" to tm, "has_mask_input" to th
                                    )
                                    decoder.run(inputs).use { res ->
                                        val masks = (res[0] as OnnxTensor).floatBuffer
                                        val iou = (res[1] as OnnxTensor).floatBuffer
                                        val count = iou.remaining().coerceAtMost(3)
                                        var idx = 0
                                        var best = -1e9f
                                        for (i in 0 until count) if (iou.get(i) > best) { best = iou.get(i); idx = i }
                                        upsample(masks, idx * 256 * 256, w, h)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Ingrandimento bilineare dei valori (logit) 256×256 a w×h, poi soglia a 0. */
    private fun upsample(m: FloatBuffer, off: Int, w: Int, h: Int): BooleanArray? {
        val out = BooleanArray(w * h)
        var any = false
        val xi0 = IntArray(w)
        val xi1 = IntArray(w)
        val xf = FloatArray(w)
        for (x in 0 until w) {
            val fx = ((x + 0.5f) * 256f / w - 0.5f).coerceIn(0f, 255f)
            xi0[x] = fx.toInt()
            xi1[x] = minOf(255, xi0[x] + 1)
            xf[x] = fx - xi0[x]
        }
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * 256f / h - 0.5f).coerceIn(0f, 255f)
            val y0 = fy.toInt()
            val y1 = minOf(255, y0 + 1)
            val ay = fy - y0
            val r0 = off + y0 * 256
            val r1 = off + y1 * 256
            for (x in 0 until w) {
                val a = xf[x]
                val top = m.get(r0 + xi0[x]) * (1 - a) + m.get(r0 + xi1[x]) * a
                val bot = m.get(r1 + xi0[x]) * (1 - a) + m.get(r1 + xi1[x]) * a
                if (top * (1 - ay) + bot * ay > 0f) { out[y * w + x] = true; any = true }
            }
        }
        return if (any) out else null
    }

    @Synchronized
    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        embed = null
        feat0 = null
        feat1 = null
        embeddedFor = null
    }
}
