package com.marco.magictouchremove

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

enum class ExportFormat(val id: String, val label: String, val ext: String, val lossy: Boolean, val hint: String) {
    PNG("png", "PNG", "png", false, "Senza perdita, supporta la trasparenza"),
    JPEG("jpeg", "JPEG", "jpg", true, "Leggero, la qualità si regola"),
    TIFF("tiff", "TIFF", "tif", false, "Senza perdita (compressione LZW), per la stampa"),
    BMP("bmp", "BMP", "bmp", false, "Non compresso, file molto grandi");

    fun accepts(extension: String): Boolean {
        val e = extension.lowercase()
        return when (this) {
            JPEG -> e == "jpg" || e == "jpeg"
            TIFF -> e == "tif" || e == "tiff"
            else -> e == ext
        }
    }

    companion object {
        fun fromId(id: String): ExportFormat = values().firstOrNull { it.id == id } ?: PNG
    }
}

/** Risoluzioni proposte: 1 = originale. */
val EXPORT_SCALES = listOf(1f, 0.75f, 0.5f, 0.45f, 0.25f)

fun scaleLabel(s: Float) = if (s >= 0.999f) "Originale" else "${(s * 100).roundToInt()}%"

class ExportOptions(val format: ExportFormat, val quality: Int, val scale: Float)

fun scaledSize(img: BufferedImage, scale: Float): Pair<Int, Int> =
    if (scale >= 0.999f) img.width to img.height
    else max(1, (img.width * scale).roundToInt()) to max(1, (img.height * scale).roundToInt())

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000L -> "%.2f GB".format(b / 1e9)
    b >= 1_000_000L -> "%.1f MB".format(b / 1e6)
    else -> "%d KB".format(maxOf(1L, b / 1000L))
}

/** Codifica l'immagine con formato, qualità (solo JPEG, 80–100) e risoluzione scelti. */
fun encodeImage(img: BufferedImage, o: ExportOptions): ByteArray {
    val (w, h) = scaledSize(img, o.scale)
    val src = if (w == img.width && h == img.height) img else scaled(img, w, h)
    val out = ByteArrayOutputStream()
    when (o.format) {
        ExportFormat.JPEG -> {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = o.quality.coerceIn(80, 100) / 100f
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(toRgb(src), null, null), param)
            }
            writer.dispose()
        }
        ExportFormat.PNG -> if (!ImageIO.write(src, "png", out)) error("formato PNG non disponibile")
        ExportFormat.BMP -> if (!ImageIO.write(toRgb(src), "bmp", out)) error("formato BMP non disponibile")
        ExportFormat.TIFF -> {
            val writers = ImageIO.getImageWritersByFormatName("tiff")
            if (!writers.hasNext()) error("formato TIFF non disponibile")
            val writer = writers.next()
            val param = writer.defaultWriteParam
            if (param.canWriteCompressed()) {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionType = "LZW"
            }
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(src, null, null), param)
            }
            writer.dispose()
        }
    }
    return out.toByteArray()
}
