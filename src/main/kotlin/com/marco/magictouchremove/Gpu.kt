package com.marco.magictouchremove

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

class GpuAdapter(val index: Int, val name: String, val vramMb: Long, val software: Boolean)

/** Accelerazione GPU (DirectML, qualsiasi scheda video di Windows 10/11) con ritorno automatico alla CPU. */
object Gpu {
    /** Le librerie DirectML sono incluse in questa installazione? */
    var available by mutableStateOf(false); private set
    /** L'ultimo modello caricato sta davvero usando la GPU. */
    var active by mutableStateOf(false); private set
    private var failed = false
    private var nativeDir: File? = null

    /** Schede video viste da Windows (DXGI), nell'ordine usato da DirectML. */
    var adapters by mutableStateOf<List<GpuAdapter>>(emptyList()); private set

    /** -1 = automatico (la scheda con più memoria dedicata); altrimenti l'indice scelto dall'utente. */
    var device: Int
        get() = Prefs.get("gpuDevice", "-1").toIntOrNull() ?: -1
        set(v) {
            Prefs.set("gpuDevice", v.toString())
            bad.clear(); Prefs.set("gpuBad", "")
            failed = false
            Engines.releaseAll()
        }

    private fun vcall(obj: Pointer, idx: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(obj.getPointer(0).getPointer(idx * 8L))
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    private fun enumerateAdapters(): List<GpuAdapter> {
        val list = ArrayList<GpuAdapter>()
        try {
            val create = NativeLibrary.getInstance("dxgi").getFunction("CreateDXGIFactory1")
            val iid = Memory(16)
            iid.setInt(0, 0x770aae78); iid.setShort(4, 0xf26f.toShort()); iid.setShort(6, 0x4dba.toShort())
            byteArrayOf(0xa8.toByte(), 0x29, 0x25, 0x3c, 0x83.toByte(), 0xd1.toByte(), 0xb3.toByte(), 0x87.toByte()).forEachIndexed { i, b -> iid.setByte(8L + i, b) }
            val pf = PointerByReference()
            if (create.invokeInt(arrayOf<Any?>(iid, pf)) != 0) return list
            val factory = pf.value
            var i = 0
            while (true) {
                val pa = PointerByReference()
                if (vcall(factory, 12, i, pa) != 0) break   // IDXGIFactory1::EnumAdapters1
                val ad = pa.value
                val d = Memory(312)
                vcall(ad, 10, d)                             // IDXGIAdapter1::GetDesc1
                list.add(GpuAdapter(i, d.getWideString(0), d.getLong(272) / (1024 * 1024), (d.getInt(304) and 2) != 0))
                vcall(ad, 2)                                 // Release
                i++
            }
            vcall(factory, 2)
        } catch (e: Throwable) {
            AppLog.w("Elenco delle schede video non disponibile: ${e.javaClass.simpleName}: ${e.message}")
        }
        return list
    }

    /** Sceglie la scheda: quella indicata dall'utente, altrimenti la più potente (più memoria dedicata). */
    private fun pickDevice(): Int {
        val d = device
        if (d >= 0 && adapters.any { it.index == d }) return d
        return adapters.filter { !it.software }.maxByOrNull { it.vramMb }?.index ?: 0
    }

    private fun nativeHandle(o: Any): Long {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("nativeHandle")
                f.isAccessible = true
                return f.getLong(o)
            } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        error("handle nativo non trovato")
    }

    /** Il binario Java di ONNX Runtime non include DirectML: si chiama direttamente la funzione della DLL con DirectML. */
    private fun appendDirectML(o: OrtSession.SessionOptions, device: Int) {
        try {
            val lib = NativeLibrary.getInstance(File(nativeDir!!, "onnxruntime.dll").absolutePath)
            val fn = lib.getFunction("OrtSessionOptionsAppendExecutionProvider_DML")
            val status = fn.invokePointer(arrayOf<Any?>(Pointer(nativeHandle(o)), device))
            if (status != null) error("ONNX Runtime ha rifiutato DirectML sulla scheda $device")
        } catch (e: Throwable) {
            AppLog.w("Attivazione diretta di DirectML non riuscita (${e.javaClass.simpleName}: ${e.message}), provo il metodo standard")
            o.addDirectML(device)
        }
    }

    var enabled: Boolean
        get() = Prefs.get("useGpu", "1") == "1"
        set(v) {
            Prefs.set("useGpu", if (v) "1" else "0")
            bad.clear(); Prefs.set("gpuBad", "")
            failed = false
            Engines.releaseAll()   // i modelli si ricaricano con la nuova impostazione
        }

    /** Va chiamata prima di usare ONNX Runtime. */
    fun init() {
        adapters = enumerateAdapters()
        AppLog.i("Schede video: " + adapters.joinToString("; ") { "#${it.index} ${it.name} (${it.vramMb} MB)" }.ifEmpty { "nessuna rilevata" })
        try {
            val dir = System.getProperty("compose.application.resources.dir")?.let { File(it) } ?: return
            val dml = File(dir, "DirectML.dll")
            val ort = File(dir, "onnxruntime.dll")
            val jni = File(dir, "onnxruntime4j_jni.dll")
            if (!dml.isFile || !ort.isFile || !jni.isFile) {
                AppLog.i("Libreria GPU (DirectML) non inclusa: calcoli sulla CPU")
                return
            }
            nativeDir = dir
            System.setProperty("onnxruntime.native.path", dir.path)
            System.load(dml.absolutePath)
            System.load(ort.absolutePath)
            System.load(jni.absolutePath)
            available = true
            AppLog.i("Libreria GPU (DirectML) caricata")
            runCatching { AppLog.i("Provider ONNX Runtime disponibili: " + OrtEnvironment.getAvailableProviders()) }
        } catch (e: Throwable) {
            AppLog.w("Libreria GPU non caricata: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun baseOptions(): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        o.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 16))
        return o
    }

    // modelli per cui la GPU ha dato errore (si ricordano anche al prossimo avvio)
    private val bad: MutableSet<String> = Prefs.get("gpuBad", "").split(',').filter { it.isNotBlank() }.toMutableSet()
    private val recent = mutableListOf<String>()   // modelli caricati sulla GPU nello stesso gruppo (pochi secondi)
    private var recentAt = 0L

    fun session(env: OrtEnvironment, path: String): OrtSession {
        val name = File(path).name
        if (name in bad) {
            AppLog.i("«$name» usa la CPU (la GPU aveva dato errore con questo modello)")
            active = false
            return env.createSession(path, baseOptions())
        }
        if (enabled && available && !failed) {
            try {
                val o = baseOptions()
                o.setMemoryPatternOptimization(false)
                o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                val dev = pickDevice()
                appendDirectML(o, dev)
                val s = env.createSession(path, o)
                active = true
                val now = System.currentTimeMillis()
                if (now - recentAt > 3000) recent.clear()
                recentAt = now
                recent.add(name)
                AppLog.i("«${File(path).name}» caricato sulla GPU #$dev (${adapters.firstOrNull { it.index == dev }?.name ?: "?"}) con DirectML")
                return s
            } catch (e: Throwable) {
                AppLog.w("GPU non utilizzabile per «${File(path).name}» (${e.message}): uso la CPU")
                failed = true
            }
        }
        active = false
        return env.createSession(path, baseOptions())
    }

    /** Un calcolo sulla GPU è fallito: quel modello passa alla CPU (anche ai prossimi avvii), gli altri restano sulla GPU. */
    fun fallbackToCpu(cause: Throwable? = null) {
        if (!active) return
        AppLog.w("Errore durante il calcolo sulla GPU (${cause?.javaClass?.simpleName}: ${cause?.message}): questo modello passa alla CPU")
        bad.addAll(recent)
        recent.clear()
        Prefs.set("gpuBad", bad.joinToString(","))
        active = false
        // le sessioni DirectML in errore NON si chiudono: farlo manda in crash ONNX Runtime
        Engines.dropAll()
    }
}
