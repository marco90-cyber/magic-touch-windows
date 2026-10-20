package com.marco.magictouchremove

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO

/** Punto e rettangolo in coordinate immagine (equivalenti di PointF / RectF di Android). */
class PointF(val x: Float, val y: Float)
class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)

fun BufferedImage.pixels(): IntArray = getRGB(0, 0, width, height, null, 0, width)

fun imageOf(w: Int, h: Int, px: IntArray): BufferedImage {
    val b = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    b.setRGB(0, 0, w, h, px, 0, w)
    return b
}

fun BufferedImage.copyImage(): BufferedImage {
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.drawImage(this, 0, 0, null)
    g.dispose()
    return out
}

fun BufferedImage.crop(x: Int, y: Int, w: Int, h: Int): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.drawImage(this, -x, -y, null)
    g.dispose()
    return out
}

private fun resizeOnce(src: BufferedImage, w: Int, h: Int): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    return out
}

/** Ridimensiona con qualità: per grandi riduzioni dimezza a passi, evitando l'aliasing. */
fun scaled(src: BufferedImage, w: Int, h: Int): BufferedImage {
    var cur = src
    while (cur.width / 2 >= w && cur.height / 2 >= h) {
        cur = resizeOnce(cur, cur.width / 2, cur.height / 2)
    }
    return if (cur.width == w && cur.height == h) cur else resizeOnce(cur, w, h)
}

fun toRgb(src: BufferedImage): BufferedImage {
    val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, src.width, src.height)
    g.drawImage(src, 0, 0, null)
    g.dispose()
    return out
}

/** Legge l'orientamento EXIF (1..8) di un JPEG; 1 se assente o illeggibile. */
private fun exifOrientation(file: File): Int {
    try {
        RandomAccessFile(file, "r").use { f ->
            if (f.readUnsignedShort() != 0xFFD8) return 1
            for (iter in 0 until 24) {
                val marker = f.readUnsignedShort()
                if (marker and 0xFF00 != 0xFF00) return 1
                if (marker == 0xFFDA) return 1
                val len = f.readUnsignedShort()
                if (marker == 0xFFE1) {
                    val buf = ByteArray(len - 2)
                    f.readFully(buf)
                    if (buf.size < 14 || String(buf, 0, 4, Charsets.US_ASCII) != "Exif") return 1
                    val le = buf[6].toInt() == 0x49
                    fun u16(o: Int): Int =
                        if (le) (buf[o].toInt() and 255) or ((buf[o + 1].toInt() and 255) shl 8)
                        else ((buf[o].toInt() and 255) shl 8) or (buf[o + 1].toInt() and 255)
                    fun u32(o: Int): Int =
                        if (le) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
                    val ifd = 6 + u32(10)
                    val n = u16(ifd)
                    for (i in 0 until n) {
                        val e = ifd + 2 + i * 12
                        if (u16(e) == 0x0112) return u16(e + 8)
                    }
                    return 1
                } else {
                    f.skipBytes(len - 2)
                }
            }
        }
    } catch (_: Exception) {
        return 1
    }
    return 1
}

private fun applyOrientation(src: BufferedImage, o: Int): BufferedImage {
    if (o <= 1 || o > 8) return src
    val w = src.width
    val h = src.height
    val swap = o >= 5
    val dw = if (swap) h else w
    val dh = if (swap) w else h
    val sp = src.pixels()
    val dp = IntArray(dw * dh)
    for (y in 0 until dh) {
        for (x in 0 until dw) {
            val sx: Int
            val sy: Int
            when (o) {
                2 -> { sx = w - 1 - x; sy = y }
                3 -> { sx = w - 1 - x; sy = h - 1 - y }
                4 -> { sx = x; sy = h - 1 - y }
                5 -> { sx = y; sy = x }
                6 -> { sx = y; sy = h - 1 - x }
                7 -> { sx = w - 1 - y; sy = h - 1 - x }
                else -> { sx = w - 1 - y; sy = x }
            }
            dp[y * dw + x] = sp[sy * w + sx]
        }
    }
    return imageOf(dw, dh, dp)
}

/** Apre un'immagine (JPEG, PNG, BMP…) rispettando l'orientamento EXIF. Ritorna null se non leggibile. */
private const val MAX_PIXELS = 40_000_000L

/** Decodifica con ImageIO; le foto enormi vengono lette direttamente a risoluzione ridotta (meno RAM). */
private fun decode(input: Any): BufferedImage? {
    ImageIO.createImageInputStream(input).use { iis ->
        val readers = ImageIO.getImageReaders(iis)
        if (!readers.hasNext()) return null
        val r = readers.next()
        try {
            r.input = iis
            val w = r.getWidth(0)
            val h = r.getHeight(0)
            val param = r.defaultReadParam
            val px = w.toLong() * h
            if (px > MAX_PIXELS) {
                val f = Math.ceil(Math.sqrt(px / MAX_PIXELS.toDouble())).toInt()
                param.setSourceSubsampling(f, f, 0, 0)
                AppLog.i("Foto molto grande (${w}×${h} px, ${px / 1_000_000} MP): ridotta di $f volte in lettura per risparmiare memoria")
            }
            return r.read(0, param)
        } finally {
            r.dispose()
        }
    }
}

/** Alcuni JPEG (MPO, foto con anteprime incollate) hanno più immagini: tengo solo la prima. */
private fun firstJpegOnly(b: ByteArray): ByteArray? {
    if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
    var i = 2
    while (i + 3 < b.size) {
        if (b[i] != 0xFF.toByte()) { i++; continue }
        val m = b[i + 1].toInt() and 0xFF
        when {
            m == 0xFF -> i++
            m == 0x00 || m == 0x01 || m in 0xD0..0xD8 -> i += 2
            m == 0xD9 -> return b.copyOf(i + 2)
            else -> i += 2 + (((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF))
        }
    }
    return null
}

fun loadImage(file: File): BufferedImage? {
    var raw: BufferedImage? = try {
        decode(file)
    } catch (e: Throwable) {
        if (e is OutOfMemoryError) throw e
        AppLog.w("Lettura standard non riuscita (${e.javaClass.simpleName}: ${e.message}), provo a leggere solo la prima immagine del file")
        null
    }
    if (raw == null) {
        raw = try {
            firstJpegOnly(file.readBytes())?.let { decode(java.io.ByteArrayInputStream(it)) }
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            AppLog.w("Anche la lettura della prima immagine è fallita (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }
    if (raw == null) raw = readWithToolkit(file)
    val img = raw ?: return null
    val argb = if (img.type == BufferedImage.TYPE_INT_ARGB) img else img.copyImage()
    val isJpeg = file.extension.lowercase().let { it == "jpg" || it == "jpeg" }
    return if (isJpeg) applyOrientation(argb, exifOrientation(file)) else argb
}

/** Decodificatore alternativo (AWT): accetta anche JPEG con profili colore o formati che ImageIO rifiuta. */
private fun readWithToolkit(file: File): BufferedImage? {
    val img = java.awt.Toolkit.getDefaultToolkit().createImage(file.absolutePath)
    val tracker = java.awt.MediaTracker(java.awt.Canvas())
    tracker.addImage(img, 0)
    tracker.waitForID(0)
    val w = img.getWidth(null)
    val h = img.getHeight(null)
    if (w <= 0 || h <= 0 || tracker.isErrorAny) return null
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.drawImage(img, 0, 0, null)
    g.dispose()
    img.flush()
    return out
}
