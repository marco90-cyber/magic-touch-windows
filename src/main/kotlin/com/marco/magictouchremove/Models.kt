package com.marco.magictouchremove

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Properties

enum class Kind { INPAINT, SELECT, GENERATE, UPSCALE, BGREMOVE }

/** [sha256]/[md5] nulli = nessun controllo di integrità; [exactSize] false = la dimensione è approssimativa. */
class ModelFile(
    val name: String, val size: Long, val sha256: String?, val url: String,
    val md5: String? = null, val exactSize: Boolean = true,
)

class ModelOption(
    val id: String,
    val kind: Kind,
    val title: String,
    val desc: String,
    val license: String,
    val files: List<ModelFile>,
) {
    val totalSize: Long get() = files.sumOf { it.size }
}

private const val REL = "https://github.com/marco90-cyber/Magic-touch-remove/releases/download/"

val OPT_LAMA = ModelOption(
    "lama", Kind.INPAINT, "LaMa",
    "Il più preciso: ricostruisce bene oggetti grandi e sfondi complessi. Più lento.",
    "Apache-2.0",
    listOf(ModelFile("lama_fp32.onnx", 208044816L, "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6", REL + "model-v1/lama_fp32.onnx"))
)

val OPT_MIGAN = ModelOption(
    "migan", Kind.INPAINT, "MI-GAN",
    "Leggero e veloce, ottimo per piccoli oggetti.",
    "MIT",
    listOf(ModelFile("migan_pipeline_v2.onnx", 28079181L, "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b", REL + "migan-v1/migan_pipeline_v2.onnx"))
)

val OPT_MOBILESAM = ModelOption(
    "mobilesam", Kind.SELECT, "MobileSAM",
    "Selezione automatica veloce degli oggetti.",
    "MIT",
    listOf(
        ModelFile("mobile_sam_image_encoder.onnx", 28157093L, "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749", REL + "sam-v1/mobile_sam_image_encoder.onnx"),
        ModelFile("sam_mask_decoder_multi.onnx", 16496559L, "8976b90a87ba50a6a72217a5ff994f7d25ce16f2229fcc1ed259e1294c622ffe", REL + "sam-v1/sam_mask_decoder_multi.onnx"),
    )
)

val OPT_SAM2 = ModelOption(
    "sam2", Kind.SELECT, "SAM 2 Tiny",
    "Selezione più accurata sui bordi, un po' più lenta.",
    "Apache-2.0",
    listOf(
        ModelFile("sam2_hiera_tiny.encoder.onnx", 134261315L, "4cc015ee18520e93f8c7ddfeaca7436039daaaaf19721b4b96a8810a805e82f7", REL + "sam2-v1/sam2_hiera_tiny.encoder.onnx"),
        ModelFile("sam2_hiera_tiny.decoder.onnx", 20640886L, "f5a4bd656c143899fb7f52d64ed81e6f6aeb37d477a0b6da50146ac7cf2187bf", REL + "sam2-v1/sam2_hiera_tiny.decoder.onnx"),
    )
)

private const val HF_SD = "https://huggingface.co/jdp8/stable-diffusion-2-inpainting-onnx-fp32/resolve/main/"

val OPT_SD2_INPAINT = ModelOption(
    "sd2inpaint", Kind.GENERATE, "Stable Diffusion 2 Inpainting",
    "Genera nuovi contenuti nella zona selezionata a partire da una descrizione, oppure ricostruisce l'area togliendo l'oggetto. " +
        "Molto pesante: lento sulla CPU, molto meglio con la GPU.",
    "CreativeML OpenRAIL++-M",
    listOf(
        ModelFile("sd2inpaint/text_encoder/model.onnx", 1362015860L, "42fb4ddd6d9d1d541e5af8e959906c8ac9e634641fc14e29537713dbec715a87", HF_SD + "text_encoder/model.onnx"),
        ModelFile("sd2inpaint/unet/model.onnx", 1209676L, "2ed482445f17de790d1208fb2bc34a28fa4f4a04d140499f28420bf2affd886a", HF_SD + "unet/model.onnx"),
        ModelFile("sd2inpaint/unet/model.onnx_data", 3463700480L, "1bc3224942cf08568adc2fd8d08f5f99940f3623eb1778f2c6e989e55e0cc73f", HF_SD + "unet/model.onnx_data"),
        ModelFile("sd2inpaint/vae_encoder/model.onnx", 136760338L, "3a538424c9921b4bd51fa87f302ca0f3f4f6ec657eb0288dc9f7626b5dc878ca", HF_SD + "vae_encoder/model.onnx"),
        ModelFile("sd2inpaint/vae_decoder/model.onnx", 198078191L, "6049e1c044b145d6338b4a57a9c6aae2d97424f7d943ad1dd57a480fe140a731", HF_SD + "vae_decoder/model.onnx"),
        ModelFile("sd2inpaint/tokenizer/vocab.json", 1059962L, null, HF_SD + "tokenizer/vocab.json"),
        ModelFile("sd2inpaint/tokenizer/merges.txt", 524619L, null, HF_SD + "tokenizer/merges.txt"),
    )
)

private const val HF_ESR = "https://huggingface.co/JoPmt/Real_Esrgan_x2_Onnx_Tflite_Tfjs/resolve/main/"

val OPT_ESRGAN_GENERAL = ModelOption(
    "esrgan_general", Kind.UPSCALE, "Real-ESRGAN General x4 (leggero)",
    "Ingrandisce la foto di 4 volte recuperando dettagli. Veloce.",
    "BSD-3-Clause",
    listOf(ModelFile("esrgan_general_x4v3.onnx", 4871786L, "bf44aff5d58baa77945f3c090e790e4847d45ee84e28a391b90966e7e48a5b0a", HF_ESR + "ano_test/realesr-general-x4v3.onnx"))
)

val OPT_ESRGAN_X2 = ModelOption(
    "esrgan_x2", Kind.UPSCALE, "Real-ESRGAN x2 (qualità)",
    "Ingrandisce la foto di 2 volte con più qualità. Più lento.",
    "BSD-3-Clause",
    listOf(ModelFile("esrgan_x2.onnx", 67116767L, "4b650e0f8cc10057635e24ac411902a54307401fb6c9f265bf6e1eaf9ae7c949", HF_ESR + "model.onnx"))
)

private const val RMBG = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/"

val OPT_U2NETP = ModelOption(
    "u2netp", Kind.BGREMOVE, "U²-Net leggero",
    "Toglie lo sfondo velocemente, precisione discreta.",
    "Apache-2.0",
    listOf(ModelFile("u2netp.onnx", 4_700_000L, null, RMBG + "u2netp.onnx", md5 = "8e83ca70e441ab06c318d82300c84806", exactSize = false))
)

val OPT_U2NET = ModelOption(
    "u2net", Kind.BGREMOVE, "U²-Net",
    "Buona precisione per persone, oggetti e animali.",
    "Apache-2.0",
    listOf(ModelFile("u2net.onnx", 176_000_000L, null, RMBG + "u2net.onnx", md5 = "60024c5c889badc19c04ad937298a77b", exactSize = false))
)

val OPT_ISNET = ModelOption(
    "isnet", Kind.BGREMOVE, "IS-Net (alta precisione)",
    "Bordi più precisi (capelli, dettagli), più lento.",
    "Apache-2.0",
    listOf(ModelFile("isnet-general-use.onnx", 170_000_000L, null, RMBG + "isnet-general-use.onnx", md5 = "fc16ebd8b0c10d971d3513d564d01e29", exactSize = false))
)

val ALL_OPTIONS = listOf(OPT_LAMA, OPT_MIGAN, OPT_MOBILESAM, OPT_SAM2, OPT_SD2_INPAINT, OPT_ESRGAN_GENERAL, OPT_ESRGAN_X2, OPT_U2NETP, OPT_U2NET, OPT_ISNET)

fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%d MB".format(bytes / 1_000_000L)

/** Scarica e tiene traccia dei modelli IA in %LOCALAPPDATA%\MagicTouchRemove\models. */
object ModelManager {
    val dir: File get() = appDataDir
    val modelsDir = File(dir, "models")

    /** Avanzamento 0..1 dei download in corso, per id del modello. */
    val progress = mutableStateMapOf<String, Float>()
    val errors = mutableStateMapOf<String, String>()

    /** Cambia a ogni installazione, così l'interfaccia si aggiorna. */
    var revision by mutableStateOf(0)
    private val active = mutableStateMapOf<Kind, String>()

    private fun prefKey(kind: Kind) = when (kind) {
        Kind.INPAINT -> "inpaint"
        Kind.SELECT -> "select"
        else -> "model_" + kind.name.lowercase()
    }

    init {
        modelsDir.mkdirs()
        for (k in Kind.values()) active[k] = Prefs.get(prefKey(k), "")
    }

    fun file(f: ModelFile) = File(modelsDir, f.name)

    fun isInstalled(opt: ModelOption): Boolean {
        if (revision < 0) return false   // legge lo stato osservabile, così l'interfaccia si aggiorna
        return opt.files.all { val f = file(it); f.isFile && (if (it.exactSize) f.length() == it.size else f.length() > 0) }
    }

    fun isActive(opt: ModelOption) = active[opt.kind] == opt.id

    fun setActive(opt: ModelOption) {
        active[opt.kind] = opt.id
        Prefs.set(prefKey(opt.kind), opt.id)
    }

    /** Il modello da usare: quello scelto se installato, altrimenti il primo installato dello stesso tipo. */
    fun activeOption(kind: Kind): ModelOption? {
        val wanted = active[kind] ?: ""
        val list = ALL_OPTIONS.filter { it.kind == kind }
        return list.firstOrNull { it.id == wanted && isInstalled(it) } ?: list.firstOrNull { isInstalled(it) }
    }

    suspend fun download(opt: ModelOption) {
        withContext(Dispatchers.IO) {
            errors.remove(opt.id)
            progress[opt.id] = 0f
            AppLog.i("Download del modello «${opt.title}» (${formatSize(opt.totalSize)})")
            try {
                modelsDir.mkdirs()
                val total = opt.totalSize.toDouble()
                var done = 0L
                val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
                for (mf in opt.files) {
                    val target = file(mf)
                    if (target.isFile && target.length() == mf.size) {
                        done += mf.size
                        progress[opt.id] = (done / total).toFloat()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    val part = File(target.path + ".part")
                    val md = MessageDigest.getInstance("SHA-256")
                    val md5 = MessageDigest.getInstance("MD5")
                    // ripresa: se esiste un download interrotto, riparte da dove era arrivato
                    var have = 0L
                    if (part.isFile && part.length() > 0) {
                        part.inputStream().use { ins ->
                            val b = ByteArray(1 shl 20)
                            while (true) {
                                val n = ins.read(b)
                                if (n < 0) break
                                md.update(b, 0, n); md5.update(b, 0, n); have += n
                            }
                        }
                    }
                    val rb = HttpRequest.newBuilder(URI.create(mf.url)).GET()
                    if (have > 0) rb.header("Range", "bytes=$have-")
                    val resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream())
                    if (resp.statusCode() == 416 && have > 0) { part.delete(); error("Download interrotto in modo anomalo: riprova") }
                    if (resp.statusCode() !in 200..299) error("Errore ${resp.statusCode()} nel download di ${mf.name}")
                    val resumed = have > 0 && resp.statusCode() == 206
                    if (!resumed) { md.reset(); md5.reset(); have = 0 }
                    done += have
                    resp.body().use { input ->
                        java.io.FileOutputStream(part, resumed).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                md5.update(buf, 0, n)
                                done += n
                                progress[opt.id] = (done / total).toFloat().coerceAtMost(1f)
                            }
                        }
                    }
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    val hex5 = md5.digest().joinToString("") { "%02x".format(it) }
                    if ((mf.sha256 != null && !hex.equals(mf.sha256, ignoreCase = true)) ||
                        (mf.md5 != null && !hex5.equals(mf.md5, ignoreCase = true))) {
                        part.delete()
                        error("File ${mf.name} non valido (controllo di integrità fallito)")
                    }
                    target.delete()
                    if (!part.renameTo(target)) error("Impossibile salvare ${mf.name}")
                }
                revision++
                AppLog.i("Modello «${opt.title}» scaricato e verificato")
                if (activeOption(opt.kind)?.id != active[opt.kind]) setActive(opt)
            } catch (e: Exception) {
                errors[opt.id] = e.message ?: "Download non riuscito"
                AppLog.e("Download di «${opt.title}» non riuscito", e)
            } finally {
                progress.remove(opt.id)
            }
        }
    }

    /** Apre la cartella dei modelli in Esplora risorse. */
    fun openFolder() {
        try {
            modelsDir.mkdirs()
            Desktop.getDesktop().open(modelsDir)
        } catch (e: Throwable) {
            AppLog.w("Impossibile aprire la cartella dei modelli: ${e.message}")
        }
    }

    fun folderBytes(): Long = try {
        modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) {
        0L
    }

    /** Elimina tutti i modelli scaricati (e i download interrotti). */
    fun deleteAll() {
        Engines.releaseAll()   // Windows non lascia cancellare un file che è ancora aperto
        var n = 0L
        var failed = 0
        modelsDir.walkBottomUp().forEach { f ->
            if (f == modelsDir) return@forEach
            val size = if (f.isFile) f.length() else 0L
            if (f.delete()) n += size else if (f.isFile) failed++
        }
        revision++
        AppLog.i("Eliminati i modelli scaricati (${formatBytes(n)})" + if (failed > 0) ", $failed file non eliminati" else "")
    }

    fun delete(opt: ModelOption) {
        Engines.releaseAll()
        opt.files.forEach { file(it).delete() }
        revision++
        AppLog.i("Modello «${opt.title}» eliminato")
    }
}

/** Mantiene in memoria il motore del modello attivo. */
object Engines {
    @Synchronized
    fun releaseAll() {
        inpainter?.close(); inpainter = null; inpainterId = null
        segmenter?.close(); segmenter = null; segmenterId = null
        generator?.close(); generator = null
        upscaler?.close(); upscaler = null; upscalerId = null
        bgRemover?.close(); bgRemover = null; bgRemoverId = null
    }

    private var generator: SdInpainter? = null
    private var upscaler: Upscaler? = null
    private var upscalerId: String? = null
    private var bgRemover: BackgroundRemover? = null
    private var bgRemoverId: String? = null

    @Synchronized
    fun generator(): SdInpainter {
        val opt = ModelManager.activeOption(Kind.GENERATE)
            ?: error("Modello generativo non scaricato: scaricalo dal pannello «Modelli IA»")
        if (generator == null) {
            AppLog.i("Caricamento del modello generativo «${opt.title}»")
            generator = SdInpainter(java.io.File(ModelManager.modelsDir, "sd2inpaint"))
        }
        return generator!!
    }

    @Synchronized
    fun upscaler(): Upscaler {
        val opt = ModelManager.activeOption(Kind.UPSCALE)
            ?: error("Modello di ingrandimento non scaricato: scaricalo dal pannello «Modelli IA»")
        if (upscaler == null || upscalerId != opt.id) {
            upscaler?.close()
            AppLog.i("Caricamento del modello di ingrandimento «${opt.title}»")
            upscaler = Upscaler(ModelManager.file(opt.files[0]).absolutePath)
            upscalerId = opt.id
        }
        return upscaler!!
    }

    @Synchronized
    fun bgRemover(): BackgroundRemover {
        val opt = ModelManager.activeOption(Kind.BGREMOVE)
            ?: error("Modello per lo sfondo non scaricato: scaricalo dal pannello «Modelli IA»")
        if (bgRemover == null || bgRemoverId != opt.id) {
            bgRemover?.close()
            AppLog.i("Caricamento del modello per lo sfondo «${opt.title}»")
            bgRemover = BackgroundRemover(ModelManager.file(opt.files[0]).absolutePath, if (opt.id == "isnet") 1024 else 320)
            bgRemoverId = opt.id
        }
        return bgRemover!!
    }

    private var inpainter: Inpainter? = null
    private var inpainterId: String? = null
    private var segmenter: Segmenter? = null
    private var segmenterId: String? = null

    @Synchronized
    fun inpainter(): Inpainter {
        val opt = ModelManager.activeOption(Kind.INPAINT)
            ?: error("Nessun modello di rimozione: scaricalo dal pannello «Modelli IA»")
        if (inpainter == null || inpainterId != opt.id) {
            inpainter?.close()
            val path = ModelManager.file(opt.files[0]).absolutePath
            AppLog.i("Caricamento del modello di rimozione «${opt.title}»")
            inpainter = if (opt.id == "migan") MiganInpainter(path) else LamaInpainter(path)
            inpainterId = opt.id
        }
        return inpainter!!
    }

    @Synchronized
    fun segmenter(): Segmenter {
        val opt = ModelManager.activeOption(Kind.SELECT)
            ?: error("Nessun modello di selezione: scaricalo dal pannello «Modelli IA»")
        if (segmenter == null || segmenterId != opt.id) {
            segmenter?.close()
            val e = ModelManager.file(opt.files[0]).absolutePath
            val d = ModelManager.file(opt.files[1]).absolutePath
            AppLog.i("Caricamento del modello di selezione «${opt.title}»")
            segmenter = if (opt.id == "sam2") Sam2Segmenter(e, d) else MobileSamSegmenter(e, d)
            segmenterId = opt.id
        }
        return segmenter!!
    }
}
