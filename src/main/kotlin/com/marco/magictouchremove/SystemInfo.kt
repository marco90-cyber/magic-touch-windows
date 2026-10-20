package com.marco.magictouchremove

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

class InfoRow(val label: String, val value: String)
class InfoSection(val title: String, val rows: List<InfoRow>)

/** Raccoglie le informazioni sul PC: sistema, processore, memoria, schermi, schede video e accelerazione 3D. */
object SysInfo {
    var sections by mutableStateOf<List<InfoSection>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    private var loaded = false

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    suspend fun refresh(force: Boolean = false) {
        if (loading || (loaded && !force)) return
        loading = true
        try {
            withContext(Dispatchers.IO) {
                val basic = basicSections()
                sections = basic                      // la parte veloce si vede subito
                val extra = if (isWindows) windowsSections() else listOf(
                    InfoSection("Schede video", listOf(InfoRow("Rilevamento", "disponibile solo su Windows")))
                )
                sections = basic + extra
                val gpus = extra.filter { it.title.startsWith("Scheda video") }.joinToString("; ") { it.title.substringAfter(": ") }
                loaded = true
                AppLog.i("Informazioni di sistema raccolte. Schede video: " + gpus.ifEmpty { "nessuna rilevata" })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.e("Raccolta delle informazioni di sistema non riuscita", e)
        } finally {
            loading = false
        }
    }

    fun asText(): String {
        val sb = StringBuilder("Magic Touch Remove $APP_VERSION - Informazioni sistema\n")
        for (s in sections) {
            sb.append('\n').append(s.title).append('\n')
            for (r in s.rows) sb.append("  ").append(r.label.padEnd(34)).append(r.value).append('\n')
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------
    // parte veloce (dentro il programma)
    // ---------------------------------------------------------------------

    private fun basicSections(): List<InfoSection> {
        val out = ArrayList<InfoSection>()
        val rt = Runtime.getRuntime()

        out += InfoSection("Sistema operativo", listOf(
            InfoRow("Sistema", System.getProperty("os.name").orEmpty()),
            InfoRow("Versione", System.getProperty("os.version").orEmpty()),
            InfoRow("Architettura", System.getProperty("os.arch").orEmpty()),
            InfoRow("Lingua e paese", java.util.Locale.getDefault().displayName),
            InfoRow("Fuso orario", java.util.TimeZone.getDefault().id),
        ))

        val mem = ArrayList<InfoRow>()
        mem += InfoRow("Processori logici", rt.availableProcessors().toString())
        try {
            val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            if (os != null) {
                mem += InfoRow("Memoria RAM totale", formatBytes(os.totalMemorySize))
                mem += InfoRow("Memoria RAM libera", formatBytes(os.freeMemorySize))
            }
        } catch (_: Throwable) {
        }
        mem += InfoRow("Memoria massima per l'app", formatBytes(rt.maxMemory()))
        out += InfoSection("Memoria", mem)

        val disp = ArrayList<InfoRow>()
        try {
            val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            disp += InfoRow("Numero di schermi", devices.size.toString())
            devices.forEachIndexed { i, d ->
                val b = d.defaultConfiguration.bounds
                val scale = d.defaultConfiguration.defaultTransform.scaleX
                val mode = d.displayMode
                val hz = if (mode.refreshRate > 0) " · ${mode.refreshRate} Hz" else ""
                disp += InfoRow("Schermo ${i + 1}", "${mode.width}×${mode.height}$hz · scala ${(scale * 100).toInt()}% · " +
                    "posizione ${b.x},${b.y}")
            }
        } catch (e: Throwable) {
            disp += InfoRow("Schermi", "non disponibile")
        }
        out += InfoSection("Schermi", disp)

        out += InfoSection("Programma", listOf(
            InfoRow("Versione di Magic Touch Remove", APP_VERSION),
            InfoRow("Java", System.getProperty("java.version").orEmpty() + " · " + System.getProperty("java.vendor").orEmpty()),
            InfoRow("Macchina virtuale", System.getProperty("java.vm.name").orEmpty()),
            InfoRow("Motore IA", "ONNX Runtime su CPU"),
            InfoRow("Cartella dei dati", appDataDir.path),
            InfoRow("Spazio libero sul disco dei dati", diskFree(appDataDir)),
            InfoRow("Spazio usato dai modelli IA", formatBytes(folderSize(ModelManager.modelsDir))),
        ))
        return out
    }

    private fun diskFree(f: File): String {
        var d: File? = f
        while (d != null && !d.exists()) d = d.parentFile
        return if (d != null) "${formatBytes(d.usableSpace)} su ${formatBytes(d.totalSpace)}" else "non disponibile"
    }

    private fun folderSize(f: File): Long = try {
        f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) {
        0L
    }

    // ---------------------------------------------------------------------
    // parte lenta (solo Windows): PowerShell e dxdiag
    // ---------------------------------------------------------------------

    private fun runCmd(timeoutSec: Long, vararg args: String): String? = try {
        val p = ProcessBuilder(*args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val reader = java.util.concurrent.CompletableFuture.supplyAsync { String(p.inputStream.readBytes(), Charsets.UTF_8) }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else reader.get(5, TimeUnit.SECONDS)
    } catch (_: Throwable) {
        null
    }

    private fun ps(query: String): List<Map<*, *>> {
        val cmd = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $query | ConvertTo-Json -Compress"
        val txt = runCmd(30, "powershell", "-NoProfile", "-NonInteractive", "-Command", cmd)?.trim()
        if (txt.isNullOrEmpty()) return emptyList()
        return try {
            when (val v = JsonParser(txt).parse()) {
                is Map<*, *> -> listOf(v)
                is List<*> -> v.filterIsInstance<Map<*, *>>()
                else -> emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun num(v: Any?): Long? = (v as? Double)?.toLong()

    private suspend fun windowsSections(): List<InfoSection> = coroutineScope {
        val cpu = async { ps("Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors,MaxClockSpeed") }
        val sys = async { ps("Get-CimInstance Win32_ComputerSystem | Select-Object Manufacturer,Model") }
        val osr = async { ps("Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,BuildNumber,OSArchitecture") }
        val vid = async { ps("Get-CimInstance Win32_VideoController | Select-Object Name,AdapterRAM,DriverVersion,DriverDate,Status") }
        val dx = async { dxdiag() }

        val out = ArrayList<InfoSection>()

        val o = osr.await().firstOrNull()
        val s = sys.await().firstOrNull()
        val hw = ArrayList<InfoRow>()
        if (o != null) {
            (o["Caption"] as? String)?.let { hw += InfoRow("Edizione di Windows", it.trim()) }
            hw += InfoRow("Build", "${o["Version"] ?: ""} (${o["BuildNumber"] ?: ""})")
        }
        if (s != null) hw += InfoRow("Computer", "${s["Manufacturer"] ?: ""} ${s["Model"] ?: ""}".trim())
        for (c in cpu.await()) {
            hw += InfoRow("Processore", (c["Name"] as? String).orEmpty().trim())
            hw += InfoRow("Core / thread", "${num(c["NumberOfCores"]) ?: "?"} / ${num(c["NumberOfLogicalProcessors"]) ?: "?"}")
            num(c["MaxClockSpeed"])?.let { hw += InfoRow("Frequenza massima", "%.2f GHz".format(it / 1000.0)) }
        }
        if (hw.isNotEmpty()) out += InfoSection("Computer e processore", hw)

        val dxInfo = dx.await()
        val wmi = vid.await()
        if (dxInfo != null && dxInfo.gpus.isNotEmpty()) {
            dxInfo.gpus.forEachIndexed { i, g ->
                val rows = ArrayList<InfoRow>()
                for ((key, label) in GPU_KEYS) g[key]?.takeIf { it.isNotBlank() }?.let { rows += InfoRow(label, it) }
                dxInfo.system["DirectXVersion"]?.let { rows += InfoRow("Versione di DirectX", it) }
                // accelerazione 3D: prima i campi noti, poi qualunque altro campo «Acceleration» del rapporto
                val shown = ACCEL_KEYS.map { it.first }.toSet()
                for ((key, label) in ACCEL_KEYS) g[key]?.let { rows += InfoRow(label, translateState(it)) }
                for ((key, v) in g) if (key.contains("Acceleration", ignoreCase = true) && key !in shown)
                    rows += InfoRow("Accelerazione ($key)", translateState(v))
                out += InfoSection("Scheda video ${i + 1}: ${g["CardName"] ?: "sconosciuta"}", rows)
            }
        } else {
            wmi.forEachIndexed { i, g ->
                val rows = ArrayList<InfoRow>()
                rows += InfoRow("Versione del driver", (g["DriverVersion"] as? String).orEmpty())
                num(g["AdapterRAM"])?.takeIf { it > 0 }?.let { rows += InfoRow("Memoria video (limitata a 4 GB da Windows)", formatBytes(it)) }
                (g["Status"] as? String)?.let { rows += InfoRow("Stato", it) }
                rows += InfoRow("Accelerazione 3D", "dettagli non disponibili (dxdiag non ha risposto)")
                out += InfoSection("Scheda video ${i + 1}: ${(g["Name"] as? String).orEmpty().trim()}", rows)
            }
            if (wmi.isEmpty()) out += InfoSection("Schede video", listOf(InfoRow("Rilevamento", "nessuna scheda video trovata")))
        }
        out
    }

    // nomi dei campi nel rapporto XML di dxdiag (uguali in tutte le lingue di Windows)
    private val GPU_KEYS = listOf(
        "Manufacturer" to "Produttore",
        "ChipType" to "Chip",
        "DedicatedMemory" to "Memoria dedicata",
        "SharedMemory" to "Memoria condivisa",
        "DisplayMemory" to "Memoria totale",
        "DriverVersion" to "Versione del driver",
        "DriverDate" to "Data del driver",
        "DDIVersion" to "Versione DDI (Direct3D)",
        "FeatureLevels" to "Livelli di funzionalità Direct3D",
        "DriverModel" to "Modello del driver",
        "CurrentMode" to "Modalità corrente",
    )

    private val ACCEL_KEYS = listOf(
        "DDAccelerationEnabled" to "Accelerazione DirectDraw",
        "D3DAccelerationEnabled" to "Accelerazione Direct3D (3D)",
        "AGPEnabled" to "Accelerazione texture AGP",
    )

    private fun translateState(v: String): String = when (v.trim().lowercase()) {
        "true", "enabled" -> "Attiva"
        "false", "disabled" -> "Disattivata"
        "not available" -> "Non disponibile"
        else -> v
    }

    private class DxInfo(val system: Map<String, String>, val gpus: List<Map<String, String>>)

    /** Esegue «dxdiag» (rapporto XML) e ne legge DirectX, schede video e stato dell'accelerazione 3D. */
    private fun dxdiag(): DxInfo? {
        val f = File.createTempFile("mtr-dxdiag", ".xml")
        try {
            f.delete()
            runCmd(60, "dxdiag", "/whql:off", "/x", f.absolutePath)
            // dxdiag scrive il file un attimo dopo la fine del processo
            for (i in 0 until 40) { if (f.isFile && f.length() > 2000) break; Thread.sleep(500) }
            if (!f.isFile) return null
            val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            val doc = dbf.newDocumentBuilder().parse(f)
            fun children(e: org.w3c.dom.Element): Map<String, String> {
                val m = LinkedHashMap<String, String>()
                val nodes = e.childNodes
                for (i in 0 until nodes.length) {
                    val n = nodes.item(i)
                    if (n is org.w3c.dom.Element && !m.containsKey(n.tagName)) {
                        val hasChildElements = (0 until n.childNodes.length).any { n.childNodes.item(it) is org.w3c.dom.Element }
                        if (!hasChildElements) m[n.tagName] = n.textContent.trim()
                    }
                }
                return m
            }
            val sysEl = doc.getElementsByTagName("SystemInformation").item(0) as? org.w3c.dom.Element
            val system = if (sysEl != null) children(sysEl) else emptyMap()
            val gpus = ArrayList<Map<String, String>>()
            val devs = doc.getElementsByTagName("DisplayDevice")
            for (i in 0 until devs.length) (devs.item(i) as? org.w3c.dom.Element)?.let { gpus.add(children(it)) }
            return DxInfo(system, gpus)
        } catch (_: Throwable) {
            return null
        } finally {
            try { f.delete() } catch (_: Throwable) { }
        }
    }
}
