package app.liberarespacio

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Comprime fotos JPG y videos MP4 de la galería.
 *
 * Por cada lote:
 *  1. Comprime cada archivo a un temporal (sin tocar el original).
 *  2. Modo papelera: crea el archivo nuevo (pendiente, invisible) en la misma carpeta,
 *     pide confirmación para mover los originales a la papelera y luego publica el nuevo
 *     con el mismo nombre. Si el usuario cancela, borra los nuevos.
 *     Modo sobrescribir: pide permiso de escritura y reemplaza el contenido del original.
 *  3. Intenta conservar la fecha de modificación del archivo. La fecha de captura
 *     (EXIF en fotos, creation_time en videos) se copia siempre.
 *
 * Se ejecuta en un hilo de fondo; [askConsent] bloquea hasta que el usuario responde.
 */
@OptIn(UnstableApi::class)
class Compressor(
    private val context: Context,
    private val uriFor: (String) -> Uri?,
    private val askConsent: (IntentSender) -> Boolean,
    private val progress: (JSONObject) -> Unit,
) {
    data class Options(val quality: Int, val overwrite: Boolean)

    private class Skip(msg: String) : Exception(msg)

    private class Original(
        val id: String, val uri: Uri, val name: String, val mime: String, val relPath: String?,
        val volume: String, val path: String?, val size: Long, val mtimeMs: Long,
        val dateTaken: Long, val isVideo: Boolean
    )

    private class Prepared(val orig: Original, val temp: File, var newUri: Uri? = null)

    @Volatile var cancelled = false
    @Volatile private var transformer: Transformer? = null

    private val cr get() = context.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val done = JSONArray()
    private val skipped = JSONArray()
    private var datesKept = 0

    companion object {
        private const val BATCH_FILES = 30
        private const val BATCH_BYTES = 1500L * 1024 * 1024
        private const val MAX_PIXELS = 64_000_000L
        private const val MARKER_BYTES = 512 * 1024
        private val SKIP_MARKERS = listOf("MotionPhoto", "MicroVideo", "hdrgm:")

        /** Etiquetas EXIF a copiar: todas menos miniatura, estructura del archivo, MakerNote y XMP. */
        private val EXIF_TAGS: List<String> by lazy {
            val exclude = listOf(
                "THUMBNAIL", "JPEG_INTERCHANGE_FORMAT", "MAKER_NOTE", "COMPRESSION", "STRIP_",
                "ROWS_PER_STRIP", "SUBFILE_TYPE", "PIXEL_X_DIMENSION", "PIXEL_Y_DIMENSION", "XMP"
            )
            ExifInterface::class.java.fields
                .filter {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java &&
                        it.name.startsWith("TAG_") && exclude.none { ex -> it.name.contains(ex) }
                }
                .mapNotNull { it.get(null) as? String }
                .distinct()
        }
    }

    fun run(ids: List<String>, opt: Options): JSONObject {
        val tempDir = File(context.cacheDir, "compress").apply { deleteRecursively(); mkdirs() }
        var index = 0
        var userCancelled = false
        try {
            while (index < ids.size && !cancelled) {
                val batch = mutableListOf<Prepared>()
                var batchBytes = 0L
                while (index < ids.size && !cancelled && batch.size < BATCH_FILES && batchBytes < BATCH_BYTES) {
                    val id = ids[index++]
                    try {
                        val orig = readOriginal(id) ?: throw Skip("Ya no existe")
                        report("compress", index, ids.size, orig.name, -1)
                        if (!opt.overwrite && !canCreateIn(orig)) throw Skip("Carpeta protegida: usa «Sobrescribir»")
                        if (tempDir.usableSpace < orig.size * 2 + 200L * 1024 * 1024) throw Skip("Sin espacio libre suficiente")
                        val temp = File(tempDir, "$id.tmp")
                        if (orig.isVideo) compressVideo(orig, opt, temp, index, ids.size)
                        else compressImage(orig, opt, temp)
                        if (cancelled) { temp.delete(); break }
                        if (temp.length() <= 0 || temp.length() > orig.size * 0.9) {
                            temp.delete()
                            throw Skip("No se reduce lo suficiente")
                        }
                        batch += Prepared(orig, temp)
                        batchBytes += temp.length()
                    } catch (s: Skip) {
                        skip(id, s.message)
                    } catch (e: OutOfMemoryError) {
                        skip(id, "Demasiado grande para la memoria")
                    } catch (e: Exception) {
                        skip(id, "Error: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                if (batch.isEmpty()) continue
                report("confirm", index, ids.size, "", -1)
                val ok = if (opt.overwrite) finalizeOverwrite(batch) else finalizeTrash(batch)
                batch.forEach { it.temp.delete() }
                if (!ok) { userCancelled = true; break }
            }
        } finally {
            tempDir.deleteRecursively()
        }
        return JSONObject()
            .put("done", done)
            .put("skipped", skipped)
            .put("cancelled", cancelled || userCancelled)
            .put("datesKept", datesKept)
            .put("locationKept", hasLocationPermission())
    }

    /* ---------- Lectura ---------- */

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_MEDIA_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** Uri sin ubicación borrada (requiere ACCESS_MEDIA_LOCATION). */
    private fun sourceUri(uri: Uri): Uri =
        if (hasLocationPermission()) MediaStore.setRequireOriginal(uri) else uri

    private fun readOriginal(id: String): Original? {
        val uri = uriFor(id) ?: return null
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED
        )
        cr.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val path = c.getString(4)
            val fileTime = path?.let { File(it).lastModified() } ?: 0L
            return Original(
                id = id, uri = uri,
                name = c.getString(0) ?: return null,
                mime = c.getString(1) ?: "",
                relPath = c.getString(2),
                volume = c.getString(3) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                path = path,
                size = c.getLong(5),
                mtimeMs = if (fileTime > 0) fileTime else c.getLong(7) * 1000L,
                dateTaken = c.getLong(6),
                isVideo = id.startsWith("v")
            )
        }
        return null
    }

    /** Carpetas donde Android permite crear archivos nuevos de este tipo. */
    private fun canCreateIn(o: Original): Boolean {
        val top = o.relPath?.substringBefore('/') ?: return false
        val allowed = if (o.isVideo) listOf("DCIM", "Movies", "Pictures") else listOf("DCIM", "Pictures")
        return allowed.any { it.equals(top, ignoreCase = true) }
    }

    private fun readRange(uri: Uri, start: Long, length: Int): ByteArray =
        cr.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val buf = ByteBuffer.allocate(length)
                var pos = start
                while (buf.hasRemaining()) {
                    val n = ch.read(buf, pos)
                    if (n <= 0) break
                    pos += n
                }
                buf.array().copyOf(buf.position())
            }
        }

    /* ---------- Fotos ---------- */

    private fun compressImage(o: Original, opt: Options, temp: File) {
        if (o.mime != "image/jpeg") throw Skip("Solo se comprimen fotos JPG")
        val src = sourceUri(o.uri)
        val head = readRange(src, 0, MARKER_BYTES)
        val tail = readRange(src, max(0L, o.size - MARKER_BYTES), MARKER_BYTES)
        val text = String(head, Charsets.ISO_8859_1) + String(tail, Charsets.ISO_8859_1)
        if (SKIP_MARKERS.any { text.contains(it) }) throw Skip("Foto en movimiento o HDR: se omite para no perder el efecto")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(src)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw Skip("No se pudo leer la foto")
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) throw Skip("Foto demasiado grande")

        val bmp = cr.openInputStream(src)!!.use { BitmapFactory.decodeStream(it) }
            ?: throw Skip("No se pudo leer la foto")
        val w = bmp.width
        val h = bmp.height
        try {
            temp.outputStream().use {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, opt.quality, it)) throw Skip("No se pudo comprimir")
            }
        } finally {
            bmp.recycle()
        }

        // Copiar EXIF: fecha de captura, orientación, cámara y ubicación (si hay permiso)
        val srcExif = cr.openInputStream(src)!!.use { ExifInterface(it) }
        val dst = ExifInterface(temp.absolutePath)
        for (tag in EXIF_TAGS) {
            try {
                srcExif.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            } catch (_: Exception) {
            }
        }
        dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString())
        dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
        dst.saveAttributes()
    }

    /* ---------- Videos ---------- */

    private fun compressVideo(o: Original, opt: Options, temp: File, index: Int, total: Int) {
        if (o.mime != "video/mp4") throw Skip("Solo se comprimen videos MP4")
        val src = sourceUri(o.uri)
        val mmr = MediaMetadataRetriever()
        val (w, h, rot, durMs) = try {
            mmr.setDataSource(context, src)
            fun key(k: Int) = mmr.extractMetadata(k)?.toLongOrNull() ?: 0L
            listOf(
                key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                key(MediaMetadataRetriever.METADATA_KEY_DURATION)
            )
        } finally {
            mmr.release()
        }
        if (w <= 0 || h <= 0 || durMs <= 0) throw Skip("No se pudo leer el video")

        val rotated = rot == 90L || rot == 270L
        val dispW = if (rotated) h else w
        val dispH = if (rotated) w else h
        val scale = min(1.0, 1080.0 / min(dispW, dispH))
        val outW = even(dispW * scale)
        val outH = even(dispH * scale)
        val base = if (opt.quality >= 85) 10_000_000.0 else 6_000_000.0
        val target = max(1_500_000, (base * outW * outH / (1920.0 * 1080.0)).roundToInt())
        val origBitrate = o.size * 8 * 1000 / durMs
        if (origBitrate < target * 1.3) throw Skip("El video ya está comprimido")

        val created = Mp4TimestampData.unixTimeToMp4TimeSeconds(
            if (o.dateTaken > 0) o.dateTaken else o.mtimeMs
        )
        val latch = CountDownLatch(1)
        var error: String? = null
        main.post {
            try {
                val t = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(target).build()
                            )
                            .setEnableFallback(true)
                            .build()
                    )
                    .setMuxerFactory(
                        InAppMuxer.Factory.Builder()
                            .setMetadataProvider { entries ->
                                entries.removeAll { it is Mp4TimestampData }
                                entries.add(Mp4TimestampData(created, created))
                            }
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            transformer = null
                            latch.countDown()
                        }

                        override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                            transformer = null
                            error = e.message ?: "error de codificación"
                            latch.countDown()
                        }
                    })
                    .build()
                val effects = if (scale < 1.0) listOf(Presentation.createForHeight(outH)) else emptyList()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(src))
                    .setEffects(Effects(emptyList(), effects))
                    .build()
                transformer = t
                t.start(item, temp.absolutePath)
                pollProgress(t, latch, o.name, index, total)
            } catch (e: Exception) {
                error = e.message ?: "no se pudo iniciar"
                latch.countDown()
            }
        }
        latch.await()
        if (cancelled) throw Skip("Detenido")
        error?.let { throw Skip("No se pudo comprimir el video: $it") }
    }

    private fun pollProgress(t: Transformer, latch: CountDownLatch, name: String, index: Int, total: Int) {
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (transformer !== t) return
                if (cancelled) { t.cancel(); transformer = null; latch.countDown(); return }
                val pct = if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
                report("compress", index, total, name, pct)
                main.postDelayed(this, 1000)
            }
        }
        main.postDelayed(tick, 1000)
    }

    /** Cancela el video en curso: libera el hilo de fondo que espera. */
    fun cancel() {
        cancelled = true
    }

    private fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)

    /* ---------- Guardar ---------- */

    private fun finalizeTrash(batch: List<Prepared>): Boolean {
        val inserted = mutableListOf<Prepared>()
        for (p in batch) {
            val o = p.orig
            try {
                val collection = if (o.isVideo) MediaStore.Video.Media.getContentUri(o.volume)
                else MediaStore.Images.Media.getContentUri(o.volume)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, o.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, o.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, o.relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = cr.insert(collection, values) ?: throw Skip("No se pudo crear el archivo")
                p.newUri = uri
                cr.openOutputStream(uri, "w")!!.use { out -> p.temp.inputStream().use { it.copyTo(out) } }
                inserted += p
            } catch (e: Exception) {
                p.newUri?.let { runCatching { cr.delete(it, null, null) } }
                skip(o.id, "No se pudo guardar: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (inserted.isEmpty()) return true

        val sender = MediaStore.createTrashRequest(cr, inserted.map { it.orig.uri }, true).intentSender
        if (!askConsent(sender)) {
            inserted.forEach { p -> p.newUri?.let { runCatching { cr.delete(it, null, null) } } }
            return false
        }
        for (p in inserted) {
            val uri = p.newUri!!
            // El original ya está en la papelera (renombrado), así que el nombre queda libre
            val published = runCatching {
                cr.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, p.orig.name)
                }, null, null)
            }.isSuccess || runCatching {
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }.isSuccess
            if (!published) {
                skip(p.orig.id, "No se pudo publicar el archivo nuevo (el original está en la papelera)")
                continue
            }
            keepDate(uri, p.orig.mtimeMs)
            markDone(p)
        }
        return true
    }

    private fun finalizeOverwrite(batch: List<Prepared>): Boolean {
        val sender = MediaStore.createWriteRequest(cr, batch.map { it.orig.uri }).intentSender
        if (!askConsent(sender)) return false
        for (p in batch) {
            try {
                cr.openOutputStream(p.orig.uri, "wt")!!.use { out -> p.temp.inputStream().use { it.copyTo(out) } }
                keepDate(p.orig.uri, p.orig.mtimeMs)
                markDone(p)
            } catch (e: Exception) {
                skip(p.orig.id, "No se pudo escribir: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return true
    }

    /** Intenta dejar la fecha de modificación original; no todos los teléfonos lo permiten. */
    private fun keepDate(uri: Uri, mtimeMs: Long) {
        @Suppress("DEPRECATION")
        val path = runCatching {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: return
        val ok = runCatching { File(path).setLastModified(mtimeMs) }.getOrDefault(false)
        if (ok) datesKept++
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }

    private fun markDone(p: Prepared) {
        done.put(JSONObject().put("id", p.orig.id).put("before", p.orig.size).put("after", p.temp.length()))
    }

    private fun skip(id: String, reason: String?) {
        skipped.put(JSONObject().put("id", id).put("reason", reason ?: "Error"))
    }

    private fun report(phase: String, index: Int, total: Int, name: String, percent: Int) {
        progress(
            JSONObject().put("phase", phase).put("index", index).put("total", total)
                .put("name", name).put("percent", percent)
        )
    }
}
