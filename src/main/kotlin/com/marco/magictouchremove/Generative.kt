package com.marco.magictouchremove

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.sqrt

/** Tokenizzatore CLIP (BPE) compatibile con Stable Diffusion. */
class ClipTokenizer(vocabFile: File, mergesFile: File) {
    private val vocab: Map<String, Int>
    private val ranks = HashMap<String, Int>()
    private val byteEnc: Map<Int, String>
    private val cache = HashMap<String, List<String>>()

    init {
        @Suppress("UNCHECKED_CAST")
        val raw = JsonParser(vocabFile.readText()).parse() as Map<String, Any?>
        vocab = raw.mapValues { (it.value as Double).toInt() }
        mergesFile.readLines().drop(1).forEachIndexed { i, l -> if (l.isNotBlank()) ranks[l.trim()] = i }
        val bs = ArrayList<Int>()
        bs.addAll('!'.code..'~'.code); bs.addAll('¡'.code..'¬'.code); bs.addAll('®'.code..'ÿ'.code)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0 until 256) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        byteEnc = bs.indices.associate { bs[it] to String(Character.toChars(cs[it])) }
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var word = token.map { it.toString() }.toMutableList()
        if (word.isEmpty()) return word
        word[word.size - 1] = word.last() + "</w>"
        while (word.size > 1) {
            var best = Int.MAX_VALUE; var bi = -1
            for (i in 0 until word.size - 1) {
                val r = ranks[word[i] + " " + word[i + 1]] ?: continue
                if (r < best) { best = r; bi = i }
            }
            if (bi < 0) break
            word[bi] = word[bi] + word[bi + 1]
            word.removeAt(bi + 1)
        }
        cache[token] = word
        return word
    }

    /** 77 identificatori: inizio, testo, fine, poi riempimento (come nel tokenizzatore di SD 2). */
    fun encode(text: String): IntArray {
        val clean = text.trim().replace(Regex("\\s+"), " ").lowercase()
        val re = Regex("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|\\p{L}+|\\p{N}|[^\\s\\p{L}\\p{N}]+")
        val ids = ArrayList<Int>()
        ids.add(49406)
        for (m in re.findAll(clean)) {
            val tok = m.value.toByteArray(Charsets.UTF_8).joinToString("") { byteEnc[it.toInt() and 255] ?: "" }
            for (p in bpe(tok)) ids.add(vocab[p] ?: 49407)
        }
        val out = IntArray(77)
        val n = minOf(ids.size, 76)
        for (i in 0 until n) out[i] = ids[i]
        out[n] = 49407
        return out
    }
}

/** Stable Diffusion 2 Inpainting (ONNX) riusando il ritaglio 512x512 e la fusione di [Inpainter]. */
class SdInpainter(private val dir: File) : Inpainter() {
    var prompt: String = ""
    var steps: Int = 20
    var guidance: Float = 7.5f
    var onProgress: ((String) -> Unit)? = null
    var isCancelled: () -> Boolean = { false }
    private fun checkCancel() { if (isCancelled()) throw java.util.concurrent.CancellationException("annullato") }

    private val tokenizer = ClipTokenizer(File(dir, "tokenizer/vocab.json"), File(dir, "tokenizer/merges.txt"))
    private val unet: OrtSession by lazy { Gpu.session(env, File(dir, "unet/model.onnx").absolutePath) }
    private val vaeEnc: OrtSession by lazy { Gpu.session(env, File(dir, "vae_encoder/model.onnx").absolutePath) }
    private val vaeDec: OrtSession by lazy { Gpu.session(env, File(dir, "vae_decoder/model.onnx").absolutePath) }
    private var lastEmb: Pair<String, Pair<FloatArray, FloatArray>>? = null

    private fun idTensor(s: OrtSession, ids: IntArray): OnnxTensor {
        val type = (s.inputInfo[s.inputNames.first()]!!.info as TensorInfo).type
        val shape = longArrayOf(1, ids.size.toLong())
        return if (type == OnnxJavaType.INT64) OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape)
        else OnnxTensor.createTensor(env, IntBuffer.wrap(ids), shape)
    }

    /** Il codificatore del testo è grande (1,3 GB): si carica, si usa e si libera subito. */
    private fun embeddings(positive: String, negative: String): Pair<FloatArray, FloatArray> {
        val key = "$positive\u0000$negative"
        lastEmb?.let { if (it.first == key) return it.second }
        onProgress?.invoke("Lettura della descrizione…")
        val s = Gpu.session(env, File(dir, "text_encoder/model.onnx").absolutePath)
        try {
            fun enc(t: String): FloatArray = idTensor(s, tokenizer.encode(t)).use { ti ->
                s.run(mapOf(s.inputNames.first() to ti)).use { res ->
                    val b = (res[0] as OnnxTensor).floatBuffer
                    FloatArray(b.remaining()).also { b.get(it) }
                }
            }
            val r = enc(positive) to enc(negative)
            lastEmb = key to r
            return r
        } finally {
            runCatching { s.close() }
        }
    }

    private fun f32(data: FloatArray, vararg shape: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    override fun run512(px: IntArray, hole: BooleanArray, n: Int): IntArray {
        require(n == 512) { "dimensione non supportata" }
        val plane = n * n
        val lat = 64
        val lp = lat * lat
        val pos = prompt.trim().ifEmpty { "seamless natural background, photorealistic, high quality, detailed" }
        val neg = "object, text, watermark, logo, blurry, low quality, artifacts, deformed"
        val (condEmb, uncondEmb) = embeddings(pos, neg)

        // immagine mascherata in [-1, 1]
        val masked = FloatArray(3 * plane)
        for (i in 0 until plane) {
            if (hole[i]) continue
            masked[i] = ((px[i] shr 16) and 255) / 127.5f - 1f
            masked[plane + i] = ((px[i] shr 8) and 255) / 127.5f - 1f
            masked[2 * plane + i] = (px[i] and 255) / 127.5f - 1f
        }
        onProgress?.invoke("Codifica dell'immagine…")
        val maskedLat = FloatArray(4 * lp)
        f32(masked, 1, 3, n.toLong(), n.toLong()).use { t ->
            vaeEnc.run(mapOf(vaeEnc.inputNames.first() to t)).use { res ->
                val ot = res[0] as OnnxTensor
                val b = ot.floatBuffer
                // se il modello restituisce media e varianza (8 canali) si usa la media
                for (i in 0 until 4 * lp) maskedLat[i] = b.get(i) * 0.18215f
            }
        }
        // maschera a 64x64 (un blocco 8x8 con almeno un pixel da generare)
        val maskLat = FloatArray(lp)
        for (y in 0 until lat) for (x in 0 until lat) {
            var any = false
            loop@ for (yy in 0 until 8) for (xx in 0 until 8) if (hole[(y * 8 + yy) * n + x * 8 + xx]) { any = true; break@loop }
            if (any) maskLat[y * lat + x] = 1f
        }

        // pianificazione DDIM (beta lineari "scaled", come in SD 2)
        val acp = DoubleArray(1000)
        run {
            var prod = 1.0
            val b0 = sqrt(0.00085); val b1 = sqrt(0.012)
            for (i in 0 until 1000) {
                val b = (b0 + (b1 - b0) * i / 999.0).let { it * it }
                prod *= 1.0 - b
                acp[i] = prod
            }
        }
        val stepsN = steps.coerceIn(5, 50)
        val stride = 1000 / stepsN
        val rnd = Random()
        var x = FloatArray(4 * lp) { rnd.nextGaussian().toFloat() }

        val names = unet.inputNames.toList()
        val nSample = names.firstOrNull { it.contains("sample", true) } ?: names[0]
        val nTime = names.firstOrNull { it.contains("time", true) } ?: names[1]
        val nEnc = names.firstOrNull { it.contains("encoder", true) || it.contains("hidden", true) } ?: names[2]
        val timeType = (unet.inputInfo[nTime]!!.info as TensorInfo).type

        fun predict(sample: FloatArray, t: Int, emb: FloatArray): FloatArray {
            val tt = when (timeType) {
                OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(t.toLong())), longArrayOf(1))
                OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(t)), longArrayOf(1))
                else -> OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(t.toFloat())), longArrayOf(1))
            }
            return f32(sample, 1, 9, lat.toLong(), lat.toLong()).use { ts ->
                tt.use { _ ->
                    f32(emb, 1, 77, (emb.size / 77).toLong()).use { te ->
                        unet.run(mapOf(nSample to ts, nTime to tt, nEnc to te)).use { res ->
                            val b = (res[0] as OnnxTensor).floatBuffer
                            FloatArray(4 * lp).also { b.get(it, 0, 4 * lp) }
                        }
                    }
                }
            }
        }

        for (i in 0 until stepsN) {
            val t = (stepsN - 1 - i) * stride + 1
            checkCancel()
            onProgress?.invoke("Generazione: passo ${i + 1} di $stepsN")
            val sample = FloatArray(9 * lp)
            System.arraycopy(x, 0, sample, 0, 4 * lp)
            System.arraycopy(maskLat, 0, sample, 4 * lp, lp)
            System.arraycopy(maskedLat, 0, sample, 5 * lp, 4 * lp)
            val eu = predict(sample, t, uncondEmb)
            val ec = predict(sample, t, condEmb)
            val aT = acp[t.coerceIn(0, 999)]
            val aP = if (t - stride >= 0) acp[t - stride] else acp[0]
            val sqA = sqrt(aT); val sqB = sqrt(1 - aT)
            val sqAp = sqrt(aP); val sqBp = sqrt(1 - aP)
            val nx = FloatArray(4 * lp)
            for (k in nx.indices) {
                val eps = eu[k] + guidance * (ec[k] - eu[k])
                val x0 = (x[k] - sqB * eps) / sqA
                nx[k] = (sqAp * x0 + sqBp * eps).toFloat()
            }
            x = nx
        }

        onProgress?.invoke("Decodifica del risultato…")
        val dec = FloatArray(4 * lp) { x[it] / 0.18215f }
        val outPx = IntArray(plane)
        f32(dec, 1, 4, lat.toLong(), lat.toLong()).use { t ->
            vaeDec.run(mapOf(vaeDec.inputNames.first() to t)).use { res ->
                val b = (res[0] as OnnxTensor).floatBuffer
                for (i in 0 until plane) {
                    val r = ((b.get(i) + 1f) * 127.5f).toInt().coerceIn(0, 255)
                    val g = ((b.get(plane + i) + 1f) * 127.5f).toInt().coerceIn(0, 255)
                    val bl = ((b.get(2 * plane + i) + 1f) * 127.5f).toInt().coerceIn(0, 255)
                    outPx[i] = (255 shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
        }
        return outPx
    }

    override fun close() {
        runCatching { unet.close() }
        runCatching { vaeEnc.close() }
        runCatching { vaeDec.close() }
    }
}
