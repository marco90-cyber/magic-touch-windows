package com.marco.magictouchremove

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro eventi: tiene gli ultimi messaggi in memoria (per la finestra «Registro eventi»),
 * li scrive anche in %LOCALAPPDATA%\MagicTouchRemove\log.txt e si può esportare in un file .txt.
 */
object AppLog {
    class Entry(val time: Long, val level: String, val text: String) {
        fun line(): String = "${fmt().format(Date(time))} [$level] $text"
    }

    private const val MAX_ENTRIES = 3000
    private val lock = Any()
    private val items = ArrayList<Entry>()
    private val logFile: File by lazy { File(ModelManager.dir, "log.txt") }

    /** Cambia a ogni nuova riga: la finestra del registro lo legge per aggiornarsi. */
    var revision by mutableIntStateOf(0)
        private set

    private val tl = ThreadLocal<SimpleDateFormat>()
    private fun fmt(): SimpleDateFormat =
        tl.get() ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ITALY).also { tl.set(it) }

    fun i(msg: String) = add("INFO", msg)
    fun w(msg: String) = add("AVVISO", msg)
    fun e(msg: String, t: Throwable? = null) =
        add("ERRORE", if (t == null) msg else "$msg: ${t.message ?: t.javaClass.simpleName}\n${t.stackTraceToString().trim()}")

    private fun add(level: String, text: String) {
        val entry = Entry(System.currentTimeMillis(), level, text)
        synchronized(lock) {
            items.add(entry)
            while (items.size > MAX_ENTRIES) items.removeAt(0)
        }
        try {
            logFile.parentFile?.mkdirs()
            logFile.appendText(entry.line() + "\n")
        } catch (_: Exception) {
        }
        revision++
    }

    fun snapshot(): List<Entry> = synchronized(lock) { ArrayList(items) }

    fun clear() {
        synchronized(lock) { items.clear() }
        revision++
    }

    /** Tiene il file su disco sotto 1 MB: all'avvio riparte da zero se è troppo grande. */
    fun trimFileOnStart() {
        try {
            if (logFile.isFile && logFile.length() > 1_000_000L) logFile.delete()
        } catch (_: Exception) {
        }
    }

    /** Informazioni su sistema e modelli, in testa al file esportato. */
    fun header(imageInfo: String?): String {
        val rt = Runtime.getRuntime()
        val sb = StringBuilder()
        sb.append("Magic Touch Remove per Windows ").append(APP_VERSION).append('\n')
        sb.append("Esportato il ").append(fmt().format(Date())).append('\n')
        sb.append("Sistema: ").append(System.getProperty("os.name")).append(' ')
            .append(System.getProperty("os.version")).append(" (").append(System.getProperty("os.arch")).append(")\n")
        sb.append("Java: ").append(System.getProperty("java.version")).append('\n')
        sb.append("Processori: ").append(rt.availableProcessors())
            .append(" · memoria massima: ").append(rt.maxMemory() / (1024 * 1024)).append(" MB\n")
        sb.append("Cartella dati: ").append(ModelManager.dir.path).append('\n')
        sb.append("Modelli installati: ")
            .append(ALL_OPTIONS.filter { ModelManager.isInstalled(it) }.joinToString(", ") { it.title }.ifEmpty { "nessuno" })
            .append('\n')
        sb.append("Modello di rimozione in uso: ").append(ModelManager.activeOption(Kind.INPAINT)?.title ?: "nessuno").append('\n')
        sb.append("Modello di selezione in uso: ").append(ModelManager.activeOption(Kind.SELECT)?.title ?: "nessuno").append('\n')
        if (imageInfo != null) sb.append("Foto aperta: ").append(imageInfo).append('\n')
        sb.append("------------------------------------------------------------\n")
        return sb.toString()
    }

    fun asText(imageInfo: String? = null): String {
        val sb = StringBuilder(header(imageInfo))
        for (e in snapshot()) sb.append(e.line()).append('\n')
        return sb.toString()
    }

    fun export(file: File, imageInfo: String?) {
        file.writeText(asText(imageInfo), Charsets.UTF_8)
        i("Registro esportato in ${file.path}")
    }
}
