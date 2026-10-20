package com.marco.magictouchremove

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Accelerazione GPU (DirectML, qualsiasi scheda video di Windows 10/11) con ritorno automatico alla CPU. */
object Gpu {
    /** Le librerie DirectML sono incluse in questa installazione? */
    var available by mutableStateOf(false); private set
    /** L'ultimo modello caricato sta davvero usando la GPU. */
    var active by mutableStateOf(false); private set
    private var failed = false

    var enabled: Boolean
        get() = Prefs.get("useGpu", "1") == "1"
        set(v) {
            Prefs.set("useGpu", if (v) "1" else "0")
            failed = false
            Engines.releaseAll()   // i modelli si ricaricano con la nuova impostazione
        }

    /** Va chiamata prima di usare ONNX Runtime. */
    fun init() {
        try {
            val dir = System.getProperty("compose.application.resources.dir")?.let { File(it) } ?: return
            val dml = File(dir, "DirectML.dll")
            val ort = File(dir, "onnxruntime.dll")
            val jni = File(dir, "onnxruntime4j_jni.dll")
            if (!dml.isFile || !ort.isFile || !jni.isFile) {
                AppLog.i("Libreria GPU (DirectML) non inclusa: calcoli sulla CPU")
                return
            }
            System.setProperty("onnxruntime.native.path", dir.path)
            System.load(dml.absolutePath)
            System.load(ort.absolutePath)
            System.load(jni.absolutePath)
            available = true
            AppLog.i("Libreria GPU (DirectML) caricata")
        } catch (e: Throwable) {
            AppLog.w("Libreria GPU non caricata: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun baseOptions(): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        o.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 16))
        return o
    }

    fun session(env: OrtEnvironment, path: String): OrtSession {
        if (enabled && available && !failed) {
            try {
                val o = baseOptions()
                o.setMemoryPatternOptimization(false)
                o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                o.addDirectML(0)
                val s = env.createSession(path, o)
                active = true
                AppLog.i("«${File(path).name}» caricato sulla GPU (DirectML)")
                return s
            } catch (e: Throwable) {
                AppLog.w("GPU non utilizzabile per «${File(path).name}» (${e.message}): uso la CPU")
                failed = true
            }
        }
        active = false
        return env.createSession(path, baseOptions())
    }

    /** Un calcolo sulla GPU è fallito: da ora e fino al riavvio si usa la CPU. */
    fun fallbackToCpu() {
        if (!active) return
        AppLog.w("Errore durante il calcolo sulla GPU: passo alla CPU")
        failed = true
        active = false
        Engines.releaseAll()
    }
}
