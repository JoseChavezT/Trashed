package app.liberarespacio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Versión nativa y en paralelo del análisis de index.html. Mismo algoritmo:
 *  - Miniatura con lado mayor THUMB_PX (desde la miniatura del sistema).
 *  - dHash 9x8 en gris (luma 0.299/0.587/0.114), vecinos horizontales => 64 bits (lo, hi).
 *  - Nitidez: varianza del Laplaciano de 4 vecinos sobre 64x64.
 *  - Huella: SHA-256 completo hasta FULL_HASH_MAX; si no, SHA-256("size:N" + inicio + final).
 */
class Analyzer(
    private val context: Context,
    private val uriFor: (String) -> Uri?,
    private val progress: (done: Int, total: Int, cooling: Boolean, celsius: Double?) -> Unit,
) {
    /** eco: menos hilos y una pausa corta entre archivos para que el teléfono se caliente menos. */
    data class Config(val fullHashMax: Long, val partialChunk: Int, val thumbPx: Int, val eco: Boolean = false)

    fun run(jobs: JSONArray, cfg: Config): JSONObject {
        val total = jobs.length()
        val out = ConcurrentHashMap<String, JSONObject>()
        val done = AtomicInteger(0)
        val threads = if (cfg.eco) 2 else Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val thermal = Thermal(context)
        val pool = Executors.newFixedThreadPool(threads)
        var lastReport = 0L
        val lock = Any()
        for (i in 0 until total) {
            val job = jobs.getJSONObject(i)
            pool.execute {
                thermal.coolDown({ false }) { cooling, c -> progress(done.get(), total, cooling, c) }
                val id = job.getString("id")
                val r = JSONObject()
                try {
                    val uri = uriFor(id) ?: throw IllegalStateException("desconocido")
                    if (job.optBoolean("feat")) features(uri, cfg, r)
                    if (job.optBoolean("hash")) fingerprint(uri, job.getLong("size"), cfg, r)
                } catch (e: Throwable) {
                    r.put("error", true)
                }
                out[id] = r
                if (cfg.eco) Thread.sleep(ECO_PAUSE_MS)
                val n = done.incrementAndGet()
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    if (n == total || now - lastReport > 250) {
                        lastReport = now
                        progress(n, total, false, null)
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(7, TimeUnit.DAYS)
        val res = JSONObject()
        for ((k, v) in out) res.put(k, v)
        return res
    }

    companion object {
        private const val ECO_PAUSE_MS = 120L
    }

    private fun luma(c: Int): Double =
        Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114

    private fun features(uri: Uri, cfg: Config, r: JSONObject) {
        val src = context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        val s = min(1.0, cfg.thumbPx.toDouble() / max(src.width, src.height))
        val t = if (s < 1.0) Bitmap.createScaledBitmap(
            src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true
        ) else src

        // dHash
        val d = Bitmap.createScaledBitmap(t, 9, 8, true)
        val p = IntArray(9 * 8)
        d.getPixels(p, 0, 9, 0, 0, 9, 8)
        var lo = 0L
        var hi = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (luma(p[y * 9 + x]) > luma(p[y * 9 + x + 1])) {
                    if (bit < 32) lo = lo or (1L shl bit) else hi = hi or (1L shl (bit - 32))
                }
                bit++
            }
        }

        // Varianza del Laplaciano sobre 64x64
        val b = Bitmap.createScaledBitmap(t, 64, 64, true)
        val q = IntArray(64 * 64)
        b.getPixels(q, 0, 64, 0, 0, 64, 64)
        val l = FloatArray(4096) { luma(q[it]).toFloat() }
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (y in 1 until 63) {
            for (x in 1 until 63) {
                val i = y * 64 + x
                val lap = (4 * l[i] - l[i - 1] - l[i + 1] - l[i - 64] - l[i + 64]).toDouble()
                sum += lap
                sum2 += lap * lap
                n++
            }
        }
        val mean = sum / n
        r.put("lo", lo).put("hi", hi).put("blur", sum2 / n - mean * mean)

        listOf(b, d).forEach { it.recycle() }
        if (t !== src) t.recycle()
        src.recycle()
    }

    private fun fingerprint(uri: Uri, size: Long, cfg: Config, r: JSONObject) {
        val md = MessageDigest.getInstance("SHA-256")
        val cr = context.contentResolver
        if (size <= cfg.fullHashMax) {
            cr.openInputStream(uri)!!.use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val k = input.read(buf)
                    if (k < 0) break
                    md.update(buf, 0, k)
                }
            }
            r.put("hash", hex(md.digest())).put("hashType", "completa")
            return
        }
        val n = cfg.partialChunk
        cr.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                fun read(start: Long, len: Int): ByteArray {
                    val buf = ByteBuffer.allocate(len)
                    var pos = start
                    while (buf.hasRemaining()) {
                        val k = ch.read(buf, pos)
                        if (k <= 0) break
                        pos += k
                    }
                    return buf.array().copyOf(buf.position())
                }
                md.update("size:$size".toByteArray(Charsets.UTF_8))
                md.update(read(0, min(n.toLong(), size).toInt()))
                val tailStart = max(0L, size - n)
                md.update(read(tailStart, (size - tailStart).toInt()))
            }
        }
        r.put("hash", hex(md.digest())).put("hashType", "parcial")
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
