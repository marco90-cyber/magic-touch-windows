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
    val size: Long get() = if (msiUrl != null) msiSize else zipSize
}

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

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
        if (status == UpdateStatus.CHECKING || status == UpdateStatus.DOWNLOADING) return
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
                            if (msi == null && zip == null) {
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

    /** Scarica l'aggiornamento e, se è un installer .msi, lo avvia e chiude l'app. */
    suspend fun downloadAndInstall(onExit: () -> Unit) {
        val i = info ?: return
        if (status == UpdateStatus.DOWNLOADING) return
        val url = i.msiUrl ?: i.zipUrl ?: return
        val expected = if (i.msiUrl != null) i.msiSize else i.zipSize
        val isMsi = i.msiUrl != null
        status = UpdateStatus.DOWNLOADING
        progress = 0f
        error = null
        var launched = false
        withContext(Dispatchers.IO) {
            try {
                val dir = File(appDataDir, "updates").also { it.mkdirs() }
                val target = File(dir, url.substringAfterLast('/'))
                AppLog.i("Download dell'aggiornamento 1.${i.build}: ${target.name}")
                val req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "MagicTouchRemove/$APP_VERSION").GET().build()
                val resp = client().send(req, HttpResponse.BodyHandlers.ofInputStream())
                if (resp.statusCode() !in 200..299) error("download: GitHub ha risposto ${resp.statusCode()}")
                val total = (if (expected > 0) expected else resp.headers().firstValueAsLong("Content-Length").orElse(0L)).toDouble()
                var done = 0L
                val part = File(target.path + ".part")
                resp.body().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress = (done / total).toFloat().coerceAtMost(1f)
                        }
                    }
                }
                if (expected > 0 && part.length() != expected) {
                    part.delete()
                    error("file scaricato incompleto (${part.length()} di $expected byte)")
                }
                target.delete()
                if (!part.renameTo(target)) error("impossibile salvare ${target.name}")
                AppLog.i("Aggiornamento scaricato: ${target.path}")
                if (isMsi) {
                    message = "Avvio dell'installazione…"
                    ProcessBuilder("msiexec", "/i", target.absolutePath).start()
                    AppLog.i("Avviato l'installer: ${target.name}")
                    launched = true
                } else {
                    message = "Scaricato in ${target.parent}. Estrai lo zip e sostituisci la cartella dell'app."
                    try { Desktop.getDesktop().open(dir) } catch (_: Throwable) { }
                }
                status = UpdateStatus.READY
            } catch (e: Throwable) {
                AppLog.e("Installazione dell'aggiornamento non riuscita", e)
                error = e.message ?: e.toString()
                status = UpdateStatus.ERROR
            }
        }
        if (launched) {
            kotlinx.coroutines.delay(1200)
            onExit()
        }
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
