package com.marco.magictouchremove

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import java.time.Duration

const val REPO = "marco90-cyber/magic-touch-windows"
const val RELEASES_PAGE = "https://github.com/$REPO/releases"

class UpdateInfo(
    val build: Int,
    val tag: String,
    val notes: String,
    val msiUrl: String?,
    val msiSize: Long,
    val zipUrl: String?,
    val zipSize: Long,
) {
    val name: String get() = "1.$build"
    val size: Long get() = zipSize
}

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, PAUSED, READY, ERROR }

/** Tiene acceso il PC (e lo schermo) durante il download: SetThreadExecutionState di Windows. */
private interface Kernel32Power : com.sun.jna.Library {
    fun SetThreadExecutionState(flags: Int): Int
}

/** Controllo e installazione degli aggiornamenti, leggendo l'ultima release pubblicata su GitHub. */
object Updater {
    var status by mutableStateOf(UpdateStatus.IDLE); private set
    var info by mutableStateOf<UpdateInfo?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf(""); private set

    /** Finestra «Aggiornamenti» aperta. */
    var dialogOpen by mutableStateOf(false)
    /** L'utente ha chiuso la barra di avviso. */
    var bannerDismissed by mutableStateOf(false)
    /** Controllo automatico all'avvio. */
    var autoCheck by mutableStateOf(Prefs.get("autoUpdate", "true") != "false"); private set

    fun changeAutoCheck(v: Boolean) {
        autoCheck = v
        Prefs.set("autoUpdate", v.toString())
    }

    private fun client() = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    suspend fun check(manual: Boolean) {
        if (status == UpdateStatus.CHECKING || status == UpdateStatus.DOWNLOADING || status == UpdateStatus.PAUSED) return
        status = UpdateStatus.CHECKING
        error = null
        if (manual) dialogOpen = true
        withContext(Dispatchers.IO) {
            try {
                AppLog.i("Controllo aggiornamenti (versione installata $APP_VERSION)")
                val req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$REPO/releases/latest"))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MagicTouchRemove/$APP_VERSION")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build()
                val resp = client().send(req, HttpResponse.BodyHandlers.ofString())
                when (resp.statusCode()) {
                    404 -> {
                        info = null
                        status = UpdateStatus.UP_TO_DATE
                        AppLog.w("Nessuna versione pubblicata su GitHub")
                    }
                    in 200..299 -> {
                        @Suppress("UNCHECKED_CAST")
                        val root = JsonParser(resp.body()).parse() as Map<String, Any?>
                        val tag = root["tag_name"] as? String ?: error("risposta senza versione")
                        val build = tag.substringAfterLast('.').toIntOrNull() ?: error("versione «$tag» non riconosciuta")
                        var msi: String? = null; var msiSize = 0L
                        var zip: String? = null; var zipSize = 0L
                        for (a in (root["assets"] as? List<*>).orEmpty()) {
                            val m = a as? Map<*, *> ?: continue
                            val name = (m["name"] as? String ?: "").lowercase()
                            val url = m["browser_download_url"] as? String ?: continue
                            val size = (m["size"] as? Double)?.toLong() ?: 0L
                            if (name.endsWith(".msi")) { msi = url; msiSize = size }
                            else if (name.endsWith(".zip") && name.contains("portatile")) { zip = url; zipSize = size }
                        }
                        val notes = (root["body"] as? String).orEmpty().trim()
                        if (build > BuildInfo.build) {
                            if (zip == null) {
                                // release appena creata e file ancora in caricamento: riprova al prossimo controllo
                                info = null
                                status = UpdateStatus.UP_TO_DATE
                                AppLog.w("La versione 1.$build non ha ancora file scaricabili")
                            } else {
                                val prev = info?.build
                                info = UpdateInfo(build, tag, notes, msi, msiSize, zip, zipSize)
                                if (prev != build) bannerDismissed = false
                                status = UpdateStatus.AVAILABLE
                                AppLog.i("Aggiornamento disponibile: versione 1.$build")
                            }
                        } else {
                            info = null
                            status = UpdateStatus.UP_TO_DATE
                            AppLog.i("L'app è aggiornata (ultima versione 1.$build)")
                        }
                    }
                    else -> error("GitHub ha risposto ${resp.statusCode()}")
                }
            } catch (e: Throwable) {
                AppLog.e("Controllo aggiornamenti non riuscito", e)
                error = e.message ?: e.toString()
                status = UpdateStatus.ERROR
            }
        }
    }

    /** Cartella del programma (quella dell'eseguibile), se l'app gira da un pacchetto e non dallo sviluppo. */
    private fun appRoot(): File? = try {
        val cmd = ProcessHandle.current().info().command().orElse(null)
        val dir = cmd?.let { File(it).parentFile }
        if (dir != null && File(dir, "app").isDirectory && cmd.endsWith(".exe", true) && !cmd.endsWith("java.exe", true) && !cmd.endsWith("javaw.exe", true)) dir else null
    } catch (_: Throwable) {
        null
    }

    /**
     * Aggiorna SOLO i file del programma (sia nella versione installata sia in quella portatile): scarica lo zip,
     * lo estrae, chiude l'app, copia i file sopra quelli vecchi e riapre l'app. Modelli e impostazioni non vengono toccati.
     */
    /** 0 = nessuna richiesta, 1 = pausa, 2 = annulla. */
    @Volatile private var stopRequest = 0
    @Volatile private var stream: java.io.InputStream? = null
    /** Byte già scaricati (per la barra durante la pausa). */
    var downloaded by mutableStateOf(0L); private set
    var downloadTotal by mutableStateOf(0L); private set

    private val power: Kernel32Power? by lazy {
        try { com.sun.jna.Native.load("kernel32", Kernel32Power::class.java) } catch (_: Throwable) { null }
    }

    private fun keepAwake(on: Boolean) {
        // ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED
        try { power?.SetThreadExecutionState(if (on) (0x80000000L or 0x1L or 0x2L).toInt() else 0x80000000L.toInt()) } catch (_: Throwable) { }
    }

    private fun partFile(i: UpdateInfo): File? {
        val url = i.zipUrl ?: return null
        val dir = File(appDataDir, "updates").also { it.mkdirs() }
        return File(dir, url.substringAfterLast('/') + ".part")
    }

    /** Ferma il download tenendo la parte già scaricata: «Riprendi» continua da lì. */
    fun pause() {
        if (status != UpdateStatus.DOWNLOADING) return
        stopRequest = 1
        try { stream?.close() } catch (_: Throwable) { }
    }

    /** Annulla il download ed elimina la parte già scaricata. */
    fun cancel() {
        when (status) {
            UpdateStatus.DOWNLOADING -> {
                stopRequest = 2
                try { stream?.close() } catch (_: Throwable) { }
            }
            UpdateStatus.PAUSED, UpdateStatus.ERROR -> {
                info?.let { partFile(it)?.delete() }
                downloaded = 0
                progress = 0f
                error = null
                status = if (info != null) UpdateStatus.AVAILABLE else UpdateStatus.IDLE
            }
            else -> {}
        }
    }

    suspend fun downloadAndInstall(onExit: () -> Unit) {
        val i = info ?: return
        if (status == UpdateStatus.DOWNLOADING) return
        stopRequest = 0
        val url = i.zipUrl ?: run { error = "Questa versione non ha il pacchetto di aggiornamento: scaricala dalla pagina delle versioni."; status = UpdateStatus.ERROR; return }
        val root = appRoot()
        if (root == null) {
            error = "L'aggiornamento automatico funziona solo dall'app installata o portatile (non dall'ambiente di sviluppo)."
            status = UpdateStatus.ERROR
            return
        }
        status = UpdateStatus.DOWNLOADING
        downloaded = partFile(i)?.length() ?: 0L
        downloadTotal = i.zipSize
        progress = if (i.zipSize > 0) (downloaded.toFloat() / i.zipSize).coerceIn(0f, 1f) * 0.8f else 0f
        error = null
        var launched = false
        withContext(Dispatchers.IO) {
            try {
                val dir = File(appDataDir, "updates").also { it.mkdirs() }
                val zip = File(dir, url.substringAfterLast('/'))
                AppLog.i("Download dell'aggiornamento 1.${i.build}: ${zip.name}")
                val part = File(zip.path + ".part")
                val expected = i.zipSize
                var done = part.length()
                if (expected > 0 && done > expected) { part.delete(); done = 0 }
                keepAwake(true)
                if (expected <= 0 || done < expected) {
                    // Ripresa: chiede solo la parte mancante (HTTP Range)
                    val rb = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "MagicTouchRemove/$APP_VERSION")
                    if (done > 0) rb.header("Range", "bytes=$done-")
                    val resp = client().send(rb.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                    val append = when (resp.statusCode()) {
                        206 -> true
                        in 200..299 -> { done = 0; false }
                        else -> error("download: GitHub ha risposto ${resp.statusCode()}")
                    }
                    val total = (if (expected > 0) expected else resp.headers().firstValueAsLong("Content-Length").orElse(0L) + (if (append) done else 0L)).toDouble()
                    downloadTotal = total.toLong()
                    resp.body().use { input ->
                        stream = input
                        java.io.FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (stopRequest == 0) {
                                val n = try { input.read(buf) } catch (e: java.io.IOException) { if (stopRequest != 0) -1 else throw e }
                                if (n < 0 || stopRequest != 0) break
                                out.write(buf, 0, n)
                                done += n
                                downloaded = done
                                if (total > 0) progress = (done / total).toFloat().coerceAtMost(1f) * 0.8f
                            }
                        }
                    }
                    stream = null
                    when (stopRequest) {
                        1 -> {
                            AppLog.i("Download dell'aggiornamento in pausa ($done byte)")
                            status = UpdateStatus.PAUSED
                            return@withContext
                        }
                        2 -> {
                            AppLog.i("Download dell'aggiornamento annullato")
                            part.delete()
                            downloaded = 0
                            progress = 0f
                            status = UpdateStatus.AVAILABLE
                            return@withContext
                        }
                    }
                }
                if (expected > 0 && part.length() != expected) {
                    part.delete()
                    error("file scaricato incompleto (${part.length()} di $expected byte)")
                }
                zip.delete()
                if (!part.renameTo(zip)) error("impossibile salvare ${zip.name}")

                // estrazione in una cartella temporanea
                message = "Preparazione dei file…"
                val ex = File(dir, "extract")
                ex.deleteRecursively(); ex.mkdirs()
                var entries = 0
                java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val out = File(ex, e.name)
                        if (!out.canonicalPath.startsWith(ex.canonicalPath)) error("archivio non valido")
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile.mkdirs()
                            out.outputStream().use { zin.copyTo(it) }
                            entries++
                            if (entries % 50 == 0) progress = (0.8f + 0.2f * (entries % 1000) / 1000f)
                        }
                    }
                }
                val src = File(ex, "MagicTouchRemove")
                val exe = File(src, "MagicTouchRemove.exe")
                if (!exe.isFile) error("l'archivio scaricato non contiene il programma")
                val log = File(dir, "update.log")
                val script = File(dir, "apply-update.cmd")
                val target = File(root, "MagicTouchRemove.exe")
                script.writeText(
                    "@echo off\r\n" +
                        "chcp 65001 >nul\r\n" +
                        "timeout /t 3 /nobreak >nul\r\n" +
                        "robocopy \"${src.absolutePath}\" \"${root.absolutePath}\" /E /R:40 /W:2 /NFL /NDL /NJH /NJS /NP >nul\r\n" +
                        "echo Copia file terminata con codice %errorlevel% > \"${log.absolutePath}\"\r\n" +
                        "if %errorlevel% LSS 8 (\r\n" +
                        "  rmdir /s /q \"${ex.absolutePath}\"\r\n" +
                        "  del \"${zip.absolutePath}\"\r\n" +
                        ")\r\n" +
                        "start \"\" \"${target.absolutePath}\"\r\n",
                    Charsets.UTF_8
                )
                AppLog.i("Aggiornamento pronto: copia dei file in ${root.path} e riavvio")
                ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", script.absolutePath).start()
                launched = true
                message = "L'app si riavvia per completare l'aggiornamento…"
                status = UpdateStatus.READY
            } catch (e: Throwable) {
                stream = null
                if (stopRequest == 1) {
                    status = UpdateStatus.PAUSED
                } else if (stopRequest == 2) {
                    i.zipUrl?.let { File(File(appDataDir, "updates"), it.substringAfterLast('/') + ".part").delete() }
                    downloaded = 0
                    progress = 0f
                    status = UpdateStatus.AVAILABLE
                } else {
                    AppLog.e("Aggiornamento non riuscito", e)
                    // la parte scaricata resta: «Riprendi» continua da lì
                    error = (e.message ?: e.toString()) + " — puoi riprendere il download."
                    status = UpdateStatus.ERROR
                }
            } finally {
                keepAwake(false)
            }
        }
        if (launched) {
            kotlinx.coroutines.delay(800)
            onExit()
        }
    }

    /** Se un aggiornamento precedente ha lasciato un resoconto, lo trascrive nel registro. */
    fun reportLastUpdate() {
        try {
            val f = File(File(appDataDir, "updates"), "update.log")
            if (f.isFile) {
                AppLog.i("Aggiornamento precedente: " + f.readText().trim())
                f.delete()
            }
        } catch (_: Throwable) { }
    }

    fun openReleasesPage() {
        try { Desktop.getDesktop().browse(URI.create(RELEASES_PAGE)) } catch (e: Throwable) { AppLog.w("Impossibile aprire il browser: ${e.message}") }
    }
}

/** Lettore JSON minimale (basta per la risposta delle API di GitHub). */
class JsonParser(private val s: String) {
    private var i = 0

    fun parse(): Any? = value()

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> num()
        }
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = str()
            ws()
            i++ // ':'
            m[k] = value()
            ws()
            if (s[i] == ',') i++ else { i++; break }
        }
        return m
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++
        ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value())
            ws()
            if (s[i] == ',') i++ else { i++; break }
        }
        return l
    }

    private fun str(): String {
        i++ // apertura
        val sb = StringBuilder()
        while (s[i] != '"') {
            val c = s[i++]
            if (c == '\\') {
                when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            } else sb.append(c)
        }
        i++ // chiusura
        return sb.toString()
    }

    private fun num(): Double {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
        return s.substring(st, i).toDouble()
    }
}
