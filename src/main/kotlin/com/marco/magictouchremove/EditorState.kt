package com.marco.magictouchremove

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Tool(val label: String, val key: String) {
    SELECT("Selezione automatica", "W"),
    BRUSH("Pennello", "B"),
    LASSO("Lasso", "L"),
    ERASER("Gomma", "E"),
    RESTORE("Ripristina", "R"),
    HAND("Mano", "H"),
    ZOOM("Zoom", "Z"),
}

/** Uno stato della cronologia (come il pannello «Storia» di Photoshop). */
class HistoryState(val image: BufferedImage, val mask: ByteArray, val label: String)

class EditorState(private val scope: CoroutineScope) {

    // ---- documento ----
    var image by mutableStateOf<BufferedImage?>(null); private set
    var original: BufferedImage? = null; private set
    var mask: ByteArray = ByteArray(0); private set
    var fileName by mutableStateOf(""); private set
    var version by mutableIntStateOf(0); private set     // cambia a ogni modifica della maschera/immagine
    var hasMask by mutableStateOf(false); private set

    // ---- strumenti ----
    var tool by mutableStateOf(Tool.BRUSH)
    var brushSize by mutableFloatStateOf(40f)       // diametro in pixel di schermo
    var hardness by mutableFloatStateOf(0.6f)
    var maskOpacity by mutableFloatStateOf(0.55f)
    var expand by mutableFloatStateOf(3f)
    var auto by mutableStateOf(false)
    var comparing by mutableStateOf(false)
    var zoomOut by mutableStateOf(false)          // strumento Zoom: true = riduci
    var showLog by mutableStateOf(false)          // finestra «Registro eventi»
    var showExport by mutableStateOf(false)       // finestra «Salva con nome»
    var showSysInfo by mutableStateOf(false)      // finestra «Informazioni sistema»
    var ctxPos by mutableStateOf<Offset?>(null)   // menu del tasto destro (coordinate schermo nella tela)
    var exitApp: () -> Unit = {}
    var crashText by mutableStateOf<String?>(null)  // messaggio mostrato quando l'app va in errore grave

    /** Chiamata da qualsiasi thread: apre la finestra "salva il registro". */
    fun reportCrash(text: String) {
        scope.launch(Dispatchers.Main) { crashText = text }
    }

    /** Libera memoria: tiene solo lo stato corrente nella cronologia. */
    private fun dropHistory() {
        if (states.size > 1 && pos in states.indices) {
            val cur = states[pos]
            states.clear(); states.add(cur); pos = 0
        }
        System.gc()
    }

    // ---- stato di lavoro ----
    var busy by mutableStateOf(false); private set
    var busyText by mutableStateOf(""); private set
    var message by mutableStateOf<String?>(null)

    // ---- cronologia ----
    val states = mutableStateListOf<HistoryState>()
    var pos by mutableIntStateOf(-1); private set
    val canUndo: Boolean get() = pos > 0
    val canRedo: Boolean get() = pos >= 0 && pos < states.size - 1

    // ---- vista (zoom / spostamento) ----
    var zoom by mutableFloatStateOf(1f); private set
    var panX by mutableFloatStateOf(0f); private set
    var panY by mutableFloatStateOf(0f); private set
    private var viewW = 0f
    private var viewH = 0f
    private var needFit = false
    var hover by mutableStateOf<Offset?>(null)
    val viewWidth: Float get() = viewW
    val viewHeight: Float get() = viewH

    // ---- gesti in corso ----
    val lassoPts = mutableStateListOf<Offset>()   // coordinate immagine
    var dragBox by mutableStateOf<Rect?>(null)    // coordinate schermo

    private var strokeTool: Tool? = null
    private var lastX = 0f
    private var lastY = 0f

    // =====================================================================
    // apertura / salvataggio
    // =====================================================================

    private var savedImage: BufferedImage? = null
    var pendingOpen by mutableStateOf<File?>(null)   // file in attesa della risposta «salvare?»
    var dropping by mutableStateOf(false)            // trascinamento file sopra la finestra

    val dirty: Boolean get() = image != null && image !== savedImage

    /** Apre un file; se c'è un lavoro non salvato chiede prima cosa fare. */
    fun requestOpen(file: File) {
        if (busy) return
        if (dirty) pendingOpen = file else open(file)
    }

    fun open(file: File) {
        if (busy) return
        busy = true; busyText = "Apertura di ${file.name}…"
        scope.launch(Dispatchers.Default) {
            var openErr: Throwable? = null
            val img = try { loadImage(file) } catch (e: Throwable) { openErr = e; null }
            withContext(Dispatchers.Main) {
                busy = false
                if (img == null) {
                    message = "Impossibile aprire «${file.name}»"
                    if (openErr != null) AppLog.e("Apertura non riuscita: ${file.path}", openErr)
                    else AppLog.e("Apertura non riuscita: ${file.path} (formato non riconosciuto, ${file.length() / 1024} KB)")
                } else {
                    AppLog.i("Aperta «${file.name}» (${img.width}×${img.height} px)")
                    image = img
                    savedImage = img
                    transparent = false
                    original = img
                    mask = ByteArray(img.width * img.height)
                    fileName = file.name
                    hasMask = false
                    states.clear(); pos = -1
                    commit("Apri")
                    needFit = true
                    fit()
                    version++
                }
            }
        }
    }

    fun close() {
        transparent = false
        image = null; original = null; mask = ByteArray(0); fileName = ""; hasMask = false
        states.clear(); pos = -1; version++
    }

    fun save(file: File, o: ExportOptions) {
        val img = image ?: return
        if (busy) return
        busy = true; busyText = "Salvataggio…"
        scope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val r = runCatching {
                val bytes = encodeImage(img, o)
                file.writeBytes(bytes)
                bytes.size.toLong()
            }
            val err = r.exceptionOrNull()
            withContext(Dispatchers.Main) {
                busy = false
                if (err != null) {
                    message = "Salvataggio non riuscito: ${err.message}"
                    AppLog.e("Salvataggio non riuscito (${file.path})", err)
                } else {
                    val (w, h) = scaledSize(img, o.scale)
                    message = "Salvato: ${file.name}"
                    if (image === img) savedImage = img
                    pendingOpen?.let { pendingOpen = null; open(it) }
                    AppLog.i("Salvata la foto in ${file.path} · ${o.format.label}" +
                        (if (o.format.lossy) " qualità ${o.quality}%" else "") +
                        " · ${scaleLabel(o.scale)} (${w}×${h} px) · ${formatBytes(r.getOrNull() ?: 0L)} · ${System.currentTimeMillis() - t0} ms")
                }
            }
        }
    }

    // =====================================================================
    // cronologia
    // =====================================================================

    private fun commit(label: String) {
        val img = image ?: return
        while (states.size > pos + 1) states.removeAt(states.size - 1)
        states.add(HistoryState(img, mask.copyOf(), label))
        pos = states.size - 1
        while (states.size > MAX_HISTORY) { states.removeAt(0); pos-- }
        // limite di memoria: la cronologia non può occupare più di ~35% dell'heap
        val budget = Runtime.getRuntime().maxMemory() * 25 / 100
        fun used() = states.sumOf { it.image.width.toLong() * it.image.height * 4 + it.mask.size }
        while (states.size > 2 && used() > budget) { states.removeAt(0); pos-- }
    }

    fun goTo(i: Int) {
        if (busy || i < 0 || i >= states.size) return
        val s = states[i]
        image = s.image
        mask = s.mask.copyOf()
        pos = i
        hasMask = mask.any { it.toInt() != 0 }
        version++
    }

    fun undo() { if (canUndo) goTo(pos - 1) }
    fun redo() { if (canRedo) goTo(pos + 1) }

    fun invertSelection() {
        if (image == null || busy) return
        for (i in mask.indices) mask[i] = (255 - (mask[i].toInt() and 255)).toByte()
        hasMask = mask.any { it.toInt() != 0 }
        version++
        commit("Inverti selezione")
    }

    fun selectAll() {
        if (image == null || busy) return
        mask.fill(255.toByte())
        hasMask = true
        version++
        commit("Seleziona tutto")
    }

    fun deselect() {
        if (image == null || !hasMask) return
        mask.fill(0)
        hasMask = false
        version++
        commit("Deseleziona")
    }

    // =====================================================================
    // vista
    // =====================================================================

    fun onViewSize(w: Float, h: Float) {
        viewW = w; viewH = h
        if (needFit) fit()
    }

    fun fit() {
        val img = image ?: return
        if (viewW <= 0f || viewH <= 0f) return
        val z = min(viewW / img.width, viewH / img.height) * 0.94f
        zoom = z.coerceIn(0.02f, 32f)
        panX = (viewW - img.width * zoom) / 2f
        panY = (viewH - img.height * zoom) / 2f
        needFit = false
    }

    fun actualSize() = zoomTo(1f)

    fun zoomTo(z: Float) {
        val img = image ?: return
        zoom = z.coerceIn(0.02f, 32f)
        panX = (viewW - img.width * zoom) / 2f
        panY = (viewH - img.height * zoom) / 2f
    }

    fun zoomAt(factor: Float, sx: Float = viewW / 2f, sy: Float = viewH / 2f) {
        if (image == null) return
        val nz = (zoom * factor).coerceIn(0.02f, 32f)
        panX = sx - (sx - panX) * nz / zoom
        panY = sy - (sy - panY) * nz / zoom
        zoom = nz
    }

    fun pan(d: Offset) { panX += d.x; panY += d.y }

    /** Zoom su un riquadro disegnato sullo schermo (come lo strumento Zoom di Photoshop). */
    fun zoomToRect(r: Rect) {
        if (image == null || r.width < 4f || r.height < 4f || viewW <= 0f || viewH <= 0f) return
        val c = toImage(r.center)
        val nz = min(viewW / r.width, viewH / r.height).times(zoom).coerceIn(0.02f, 32f)
        zoom = nz
        panX = viewW / 2f - c.x * nz
        panY = viewH / 2f - c.y * nz
    }

    fun toImage(screen: Offset) = Offset((screen.x - panX) / zoom, (screen.y - panY) / zoom)

    // =====================================================================
    // pennello / gomma / ripristina
    // =====================================================================

    fun beginStroke(t: Tool) {
        val img = image ?: return
        strokeTool = t
        lastX = Float.NaN
        if (t == Tool.RESTORE) {
            // copy-on-write: gli stati della cronologia non devono cambiare
            image = img.copyImage()
        }
    }

    fun strokeTo(p: Offset) {
        val t = strokeTool ?: return
        val r = max(0.6f, brushSize / 2f / zoom)
        if (lastX.isNaN()) {
            stamp(t, p.x, p.y, r)
        } else {
            val dx = p.x - lastX
            val dy = p.y - lastY
            val dist = sqrt(dx * dx + dy * dy)
            val step = max(1f, r * 0.25f)
            val n = max(1, (dist / step).toInt())
            for (i in 1..n) stamp(t, lastX + dx * i / n, lastY + dy * i / n, r)
        }
        lastX = p.x; lastY = p.y
        if (t == Tool.BRUSH) hasMask = true   // la maschera si vede già durante il primo tratto
        version++
    }

    fun endStroke() {
        val t = strokeTool ?: return
        strokeTool = null
        if (t != Tool.RESTORE) hasMask = mask.any { it.toInt() != 0 }
        commit(t.label)
        if (auto && t == Tool.BRUSH && hasMask) remove()
    }

    private fun stamp(t: Tool, cx: Float, cy: Float, r: Float) {
        val img = image ?: return
        val w = img.width
        val h = img.height
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(w - 1, floor(cx + r).toInt())
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(h - 1, floor(cy + r).toInt())
        if (x1 < x0 || y1 < y0) return
        val solid = r * hardness
        val data = (img.raster.dataBuffer as? DataBufferInt)?.data
        val orig = (original?.raster?.dataBuffer as? DataBufferInt)?.data
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val d = sqrt(dx * dx + dy * dy)
                if (d > r) continue
                val a = if (d <= solid) 1f else (1f - (d - solid) / (r - solid + 1e-3f)).coerceIn(0f, 1f)
                val i = y * w + x
                when (t) {
                    Tool.BRUSH -> {
                        val v = (a * 255f).toInt()
                        if (v > (mask[i].toInt() and 255)) mask[i] = v.toByte()
                    }
                    Tool.ERASER -> {
                        val v = (mask[i].toInt() and 255) - (a * 255f).toInt()
                        mask[i] = max(0, v).toByte()
                    }
                    Tool.RESTORE -> if (data != null && orig != null && orig.size == data.size) {
                        data[i] = blend(data[i], orig[i], a)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun blend(cur: Int, src: Int, a: Float): Int {
        if (a >= 0.999f) return src
        val r = (((src shr 16) and 255) * a + ((cur shr 16) and 255) * (1 - a)).toInt()
        val g = (((src shr 8) and 255) * a + ((cur shr 8) and 255) * (1 - a)).toInt()
        val b = ((src and 255) * a + (cur and 255) * (1 - a)).toInt()
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    // =====================================================================
    // lasso
    // =====================================================================

    fun finishLasso() {
        val img = image ?: return
        val pts = lassoPts.toList()
        lassoPts.clear()
        if (pts.size < 3) return
        val w = img.width
        val h = img.height
        val gray = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = gray.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        val path = Path2D.Float()
        path.moveTo(pts[0].x.toDouble(), pts[0].y.toDouble())
        for (i in 1 until pts.size) path.lineTo(pts[i].x.toDouble(), pts[i].y.toDouble())
        path.closePath()
        g.fill(path)
        g.dispose()
        val data = (gray.raster.dataBuffer as DataBufferByte).data
        for (i in data.indices) {
            val v = data[i].toInt() and 255
            if (v > (mask[i].toInt() and 255)) mask[i] = v.toByte()
        }
        hasMask = mask.any { it.toInt() != 0 }
        version++
        commit("Lasso")
        if (auto && hasMask) remove()
    }

    // =====================================================================
    // selezione automatica (MobileSAM / SAM 2)
    // =====================================================================

    fun finishSelect(startScreen: Offset, endScreen: Offset, moved: Boolean) {
        if (image == null || busy) return
        if (moved) {
            val a = toImage(startScreen)
            val b = toImage(endScreen)
            runSelect(null, RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)))
        } else {
            val p = toImage(startScreen)
            runSelect(PointF(p.x, p.y), null)
        }
    }

    private fun runSelect(point: PointF?, box: RectF?) {
        val img = image ?: return
        busy = true; busyText = "Selezione automatica…"
        val gen = startJob()
        scope.launch(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            var r = runCatching { Engines.segmenter().select(img, point, box) }
            if (r.isFailure && Gpu.active && r.exceptionOrNull() !is OutOfMemoryError) {
                Gpu.fallbackToCpu(r.exceptionOrNull())
                r = runCatching { Engines.segmenter().select(img, point, box) }
            }
            r.exceptionOrNull()?.let { AppLog.e("Selezione automatica non riuscita", it) }
            if (r.isSuccess) AppLog.i("Selezione automatica (${if (box != null) "riquadro" else "clic"}): " +
                (if (r.getOrNull() != null) "oggetto trovato" else "nessun oggetto") + " in ${System.currentTimeMillis() - t0} ms")
            val sel: BooleanArray? = r.getOrNull()
            val err: String? = r.exceptionOrNull()?.let { it.message ?: it.toString() }
            withContext(Dispatchers.Main) {
                if (gen != jobGen) return@withContext
                busy = false; cancellable = false
                if (err != null) {
                    message = err
                } else if (sel == null || image !== img) {
                    message = "Nessun oggetto trovato qui"
                } else {
                    for (i in sel.indices) if (sel[i]) mask[i] = 255.toByte()
                    hasMask = true
                    version++
                    commit("Selezione automatica")
                    if (auto) remove()
                }
            }
        }
    }

    // =====================================================================
    // rimozione con l'IA
    // =====================================================================

    fun remove() {
        val img = image ?: return
        if (busy) return
        if (!hasMask) { message = "Seleziona prima l'oggetto da rimuovere"; return }
        busy = true; busyText = "Rimozione con l'IA…"
        val gen = startJob()
        val m = mask.copyOf()
        val ex = expand.toInt()
        val feather = ((1f - hardness) * 8f).toInt()
        scope.launch(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            var r = runCatching { Engines.inpainter().inpaint(img, m, ex, feather, DetailBoost.provider()) }
            if (r.isFailure && Gpu.active && r.exceptionOrNull() !is OutOfMemoryError) {
                Gpu.fallbackToCpu(r.exceptionOrNull())
                r = runCatching { Engines.inpainter().inpaint(img, m, ex, feather, DetailBoost.provider()) }
            }
            r.exceptionOrNull()?.let {
                AppLog.e("Rimozione non riuscita", it)
                if (it is OutOfMemoryError) {
                    withContext(Dispatchers.Main) { dropHistory() }
                    reportCrash("Memoria insufficiente durante la rimozione (immagine molto grande). " +
                        "Ho liberato la cronologia: riprova, oppure usa un'immagine più piccola.")
                }
            }
            if (r.isSuccess) AppLog.i("Rimozione con l'IA completata in ${System.currentTimeMillis() - t0} ms (espansione $ex px, sfumatura $feather)")
            val res: BufferedImage? = r.getOrNull()
            val err: String? = r.exceptionOrNull()?.let { it.message ?: it.toString() }
            withContext(Dispatchers.Main) {
                if (gen != jobGen) return@withContext
                busy = false; cancellable = false
                if (err != null) {
                    message = err
                } else if (res == null || image !== img) {
                    message = "Niente da rimuovere"
                } else {
                    image = res
                    mask = ByteArray(res.width * res.height)
                    hasMask = false
                    version++
                    commit("Cancella con IA")
                }
            }
        }
    }

    // =====================================================================
    // funzioni IA aggiuntive: generativa, ingrandimento, sfondo
    // =====================================================================

    var transparent by mutableStateOf(false)   // l'immagine ha zone trasparenti (si disegna la scacchiera)
    var showTexture by mutableStateOf(false)   // finestra «Impostazioni ricostruzione»
    var showGenerate by mutableStateOf(false)  // finestra «Genera con IA»

    @Volatile private var jobGen = 0
    var cancellable by mutableStateOf(false); private set

    private fun startJob(): Int { cancellable = true; return ++jobGen }

    /** Annulla l'operazione IA in corso: l'interfaccia si libera subito e il risultato viene scartato. */
    fun cancelJob() {
        if (!cancellable) return
        jobGen++
        cancellable = false
        busy = false
        message = "Operazione annullata"
        AppLog.i("Operazione IA annullata dall'utente")
    }

    private fun progressText(s: String) { scope.launch(Dispatchers.Main) { busyText = s } }

    /** Esegue [job] in background e, se produce un'immagine, la applica come nuovo passo della cronologia. */
    private fun imageJob(
        busyMsg: String, doneLabel: String, resetMask: Boolean, newSize: Boolean = false, alpha: Boolean = false,
        job: suspend (BufferedImage, () -> Boolean) -> BufferedImage?,
    ) {
        val img = image ?: return
        if (busy) return
        busy = true; busyText = busyMsg
        val gen = startJob()
        scope.launch(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            val cancelled = { gen != jobGen }
            var r = runCatching { job(img, cancelled) }
            if (r.isFailure && !cancelled() && Gpu.active && r.exceptionOrNull() !is OutOfMemoryError) {
                Gpu.fallbackToCpu(r.exceptionOrNull())
                r = runCatching { job(img, cancelled) }
            }
            if (!cancelled()) r.exceptionOrNull()?.let {
                AppLog.e("$doneLabel non riuscito", it)
                if (it is OutOfMemoryError) withContext(Dispatchers.Main) { dropHistory() }
            }
            if (r.isSuccess) AppLog.i("$doneLabel completato in ${System.currentTimeMillis() - t0} ms")
            val res = r.getOrNull()
            val err = r.exceptionOrNull()?.let { it.message ?: it.toString() }
            withContext(Dispatchers.Main) {
                if (gen != jobGen) return@withContext
                busy = false; cancellable = false
                if (err != null) {
                    message = err
                } else if (res == null || image !== img) {
                    message = "Niente da elaborare"
                } else {
                    image = res
                    if (newSize) { original = res; needFit = true }
                    if (resetMask || mask.size != res.width * res.height) {
                        mask = ByteArray(res.width * res.height); hasMask = false
                    }
                    if (alpha) transparent = true
                    version++
                    commit(doneLabel)
                    if (newSize) fit()
                }
            }
        }
    }

    fun generate(prompt: String, steps: Int, guidance: Float) {
        if (!hasMask) { message = "Seleziona prima la zona da generare"; return }
        val m = mask.copyOf()
        val ex = expand.toInt()
        val feather = ((1f - hardness) * 8f).toInt()
        imageJob("Generazione con l'IA…", "Generazione IA", resetMask = true) { img, cancelled ->
            val g = Engines.generator()
            g.prompt = prompt; g.steps = steps; g.guidance = guidance
            g.onProgress = { progressText(it) }
            g.isCancelled = cancelled
            g.inpaint(img, m, ex, feather, DetailBoost.provider(cancelled))
        }
    }

    fun upscale() = imageJob("Ingrandimento con l'IA…", "Ingrandimento IA", resetMask = true, newSize = true) { img, cancelled ->
        Engines.upscaler().upscale(img, cancelled) { progressText("Ingrandimento… ${(it * 100).toInt()}%") }
    }

    fun removeBackground() = imageJob("Rimozione dello sfondo…", "Rimozione sfondo", resetMask = true, alpha = true) { img, _ ->
        Engines.bgRemover().remove(img)
    }

    // =====================================================================
    // anteprima della maschera per la tela (a risoluzione ridotta)
    // =====================================================================

    private var overlayImg: BufferedImage? = null

    fun buildOverlay(): ImageBitmap? {
        val img = image ?: return null
        if (!hasMask) return null
        val w = img.width
        val h = img.height
        val s = min(1f, 1600f / max(w, h))
        val ow = max(1, (w * s).toInt())
        val oh = max(1, (h * s).toInt())
        // si riusa lo stesso buffer a ogni aggiornamento (durante il tratto del pennello è chiamata di continuo)
        val cache = overlayImg?.takeIf { it.width == ow && it.height == oh }
            ?: BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB).also { overlayImg = it }
        val px = (cache.raster.dataBuffer as DataBufferInt).data
        val xs = IntArray(ow) { min(w - 1, (it / s).toInt()) }
        for (y in 0 until oh) {
            val row = min(h - 1, (y / s).toInt()) * w
            val o = y * ow
            for (x in 0 until ow) {
                val a = mask[row + xs[x]].toInt() and 255
                px[o + x] = if (a != 0) (a shl 24) or 0xE5314B else 0   // rosso
            }
        }
        return cache.toComposeImageBitmap()
    }

    companion object {
        const val MAX_HISTORY = 30
    }
}
