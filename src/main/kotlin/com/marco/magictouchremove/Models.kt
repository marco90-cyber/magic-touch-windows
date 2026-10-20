package com.marco.magictouchremove

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Properties

enum class Kind { INPAINT, SELECT }

class ModelFile(val name: String, val size: Long, val sha256: String, val url: String)

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

val ALL_OPTIONS = listOf(OPT_LAMA, OPT_MIGAN, OPT_MOBILESAM, OPT_SAM2)

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
    var activeInpaint by mutableStateOf("lama")
    var activeSelect by mutableStateOf("mobilesam")

    init {
        modelsDir.mkdirs()
        activeInpaint = Prefs.get("inpaint", "lama")
        activeSelect = Prefs.get("select", "mobilesam")
    }

    fun file(f: ModelFile) = File(modelsDir, f.name)

    fun isInstalled(opt: ModelOption): Boolean {
        if (revision < 0) return false   // legge lo stato osservabile, così l'interfaccia si aggiorna
        return opt.files.all { val f = file(it); f.isFile && f.length() == it.size }
    }

    fun isActive(opt: ModelOption) =
        if (opt.kind == Kind.INPAINT) activeInpaint == opt.id else activeSelect == opt.id

    fun setActive(opt: ModelOption) {
        if (opt.kind == Kind.INPAINT) activeInpaint = opt.id else activeSelect = opt.id
        Prefs.set("inpaint", activeInpaint)
        Prefs.set("select", activeSelect)
    }

    /** Il modello da usare: quello scelto se installato, altrimenti il primo installato dello stesso tipo. */
    fun activeOption(kind: Kind): ModelOption? {
        val wanted = if (kind == Kind.INPAINT) activeInpaint else activeSelect
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
                    val part = File(target.path + ".part")
                    val req = HttpRequest.newBuilder(URI.create(mf.url)).GET().build()
                    val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
                    if (resp.statusCode() !in 200..299) error("Errore ${resp.statusCode()} nel download di ${mf.name}")
                    val md = MessageDigest.getInstance("SHA-256")
                    resp.body().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                done += n
                                progress[opt.id] = (done / total).toFloat().coerceAtMost(1f)
                            }
                        }
                    }
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    if (!hex.equals(mf.sha256, ignoreCase = true)) {
                        part.delete()
                        error("File ${mf.name} non valido (controllo SHA-256 fallito)")
                    }
                    target.delete()
                    if (!part.renameTo(target)) error("Impossibile salvare ${mf.name}")
                }
                revision++
                AppLog.i("Modello «${opt.title}» scaricato e verificato")
                if (opt.kind == Kind.INPAINT && activeOption(Kind.INPAINT)?.id != activeInpaint) setActive(opt)
                if (opt.kind == Kind.SELECT && activeOption(Kind.SELECT)?.id != activeSelect) setActive(opt)
            } catch (e: Exception) {
                errors[opt.id] = e.message ?: "Download non riuscito"
                AppLog.e("Download di «${opt.title}» non riuscito", e)
            } finally {
                progress.remove(opt.id)
            }
        }
    }

    fun delete(opt: ModelOption) {
        opt.files.forEach { file(it).delete() }
        revision++
        AppLog.i("Modello «${opt.title}» eliminato")
    }
}

/** Mantiene in memoria il motore del modello attivo. */
object Engines {
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
