package com.marco.magictouchremove

import java.io.File
import java.util.Properties

/** Cartella dei dati dell'app: %LOCALAPPDATA%\MagicTouchRemove (modelli, registro, impostazioni, aggiornamenti). */
val appDataDir: File by lazy {
    val local = System.getenv("LOCALAPPDATA")
    val base = if (!local.isNullOrBlank()) File(local) else File(System.getProperty("user.home"), ".local/share")
    File(base, "MagicTouchRemove")
}

/** Numero di build (quello della release su GitHub), scritto nel file version.properties durante la compilazione. */
object BuildInfo {
    val build: Int by lazy {
        try {
            BuildInfo::class.java.getResourceAsStream("/version.properties")?.use { s ->
                Properties().apply { load(s) }.getProperty("build")?.trim()?.toIntOrNull()
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }
}

val APP_VERSION: String get() = if (BuildInfo.build > 0) "1.${BuildInfo.build}" else "1.0 (sviluppo)"

/** Impostazioni salvate su disco (chiave = valore). */
object Prefs {
    private val file by lazy { File(appDataDir, "settings.properties") }
    private val props = Properties()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (file.isFile) file.inputStream().use { props.load(it) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun get(key: String, default: String): String {
        load()
        return props.getProperty(key, default)
    }

    @Synchronized
    fun set(key: String, value: String) {
        load()
        props.setProperty(key, value)
        try {
            appDataDir.mkdirs()
            file.outputStream().use { props.store(it, "Magic Touch Remove") }
        } catch (_: Exception) {
        }
    }
}
