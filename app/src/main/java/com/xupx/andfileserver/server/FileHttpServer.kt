package com.xupx.andfileserver.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import com.xupx.andfileserver.utils.Utils
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class FileHttpServer(
    private val context: Context,
    private val webDir: String = "",
    private val rootDir: String = "/sdcard",
    port: Int,
    private val token: String? = null, // 为空则关闭鉴权
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val isStatic =
            uri == "/" || uri.endsWith(".html") || uri.endsWith(".css") || uri.endsWith(".js") ||
                    uri.endsWith(".ico") || uri.endsWith(".png") || uri.endsWith(".svg")

        if (!isStatic && token != null) {
            val ok = session.headers["x-token"] == token
            if (!ok) return text(Status.UNAUTHORIZED, "Unauthorized")
        }

        return try {
            when {
                uri == "/" -> serveIndex()
                uri.endsWith(".html") -> serveHtml(uri.trimStart('/'))
                uri.endsWith(".css") -> serveAsset(uri.trimStart('/'), "text/css")
                uri.endsWith(".js") -> serveAsset(uri.trimStart('/'), "application/javascript")

                uri.endsWith(".ico") -> serveBinaryAsset(
                    uri.trimStart('/'),
                    "image/x-icon",
                    cacheForever = true
                )

                uri.endsWith(".png") -> serveBinaryAsset(
                    uri.trimStart('/'),
                    "image/png",
                    cacheForever = true
                )

                uri.endsWith(".svg") -> serveAsset(uri.trimStart('/'), "image/svg+xml")

                uri.startsWith("/ls") -> listDir(session)
                uri.startsWith("/dl") -> download(session)
                uri.startsWith("/open") -> openInline(session)
                uri.startsWith("/upload") -> upload(session)
                uri.startsWith("/mkdir") -> mkdir(session)
                uri.startsWith("/rm") -> delete(session)
                uri.startsWith("/rename") -> rename(session)
                uri.startsWith("/mv") -> move(session)
                uri.startsWith("/thumb") -> thumb(session)
                uri.startsWith("/zip") -> zip(session)
                else -> text(Status.NOT_FOUND, "Not found")
            }
        } catch (e: Exception) {
            text(Status.INTERNAL_ERROR, "Error: ${e.message}")
        }
    }

    private fun serveIndex(): Response = serveAsset("index.html", "text/html") {
        it.replace("{{rootDir}}", rootDir).replace("__TOKEN__", token ?: "")
    }

    private fun serveHtml(name: String): Response = serveAsset(name, "text/html")

    private fun serveAsset(
        name: String,
        mime: String,
        block: ((String) -> String)? = null
    ): Response =
        try {
            val filePath =
                if (!webDir.endsWith('/') && !name.startsWith('/')) "$webDir/$name" else "$webDir$name"
            val txt =
                context.assets.open(filePath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val body = block?.invoke(txt) ?: txt
            newFixedLengthResponse(Status.OK, "$mime; charset=utf-8", body)
        } catch (_: Exception) {
            text(Status.NOT_FOUND, "Asset not found: $name")
        }

    private fun serveBinaryAsset(
        name: String,
        mime: String,
        cacheForever: Boolean = false
    ): Response {
        return try {
            val filePath =
                if (!webDir.endsWith('/') && !name.startsWith('/')) "$webDir/$name" else "$webDir$name"
            val bytes = context.assets.open(filePath).use { it.readBytes() }
            return newFixedLengthResponse(
                Status.OK,
                mime,
                bytes.inputStream(),
                bytes.size.toLong()
            ).apply {
                if (cacheForever) addHeader("Cache-Control", "public, max-age=31536000, immutable")
            }
        } catch (_: Exception) {
            text(Status.NOT_FOUND, "Asset not found: $name")
        }
    }

    private fun listDir(session: IHTTPSession): Response {
        val raw = session.parameters["path"]?.firstOrNull() ?: rootDir
        val dir = safeResolve(raw) ?: return bad("invalid path")
        if (!dir.exists() || !dir.isDirectory) return notFound()
        val arr = dir.listFiles()?.sortedBy { it.lastModified() }?.reversed()?.map {
            mapOf(
                "name" to it.name,
                "path" to it.absolutePath,
                "isDir" to it.isDirectory,
                "size" to if (it.isFile) it.length().formatSize() else -1,
                "length" to if (it.isFile) it.length() else -1,
                "lastModified" to Utils.formatTimestamp(it.lastModified())
            )
        } ?: emptyList()
        return jsonOk(JSONArray(arr).toString())
    }

    private fun download(session: IHTTPSession): Response {
        val target =
            safeResolve(session.parameters["path"]?.firstOrNull() ?: return bad("missing path"))
                ?: return bad("invalid path")
        if (!target.exists() || !target.isFile) return notFound()
        val total = target.length()
        val mime = guessMime(target)
        val etag = etagOf(target)
        val lastMod = httpDate(target.lastModified())

        if (matchNotModified(session, etag, lastMod)) return notModified(etag, lastMod)
        if (session.method == Method.HEAD) return headResponse(
            mime,
            total,
            attachmentName = target.name,
            etag,
            lastMod
        )

        val range = session.headers["range"]?.lowercase()
        val fis = FileInputStream(target)
        return if (range != null && range.startsWith("bytes=")) {
            val pair = parseRange(range, total)
            if (pair == null) newFixedLengthResponse(
                Status.OK,
                mime,
                fis,
                total
            ).withCommonDownloadHeaders(target.name, etag, lastMod)
            else {
                val (start, end) = pair
                val len = (end - start + 1).coerceAtLeast(0)
                skipFully(fis, start)
                newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, boundedInput(fis, len), len)
                    .withRangeHeaders(start, end, total)
                    .withCommonDownloadHeaders(target.name, etag, lastMod)
            }
        } else {
            newFixedLengthResponse(
                Status.OK,
                mime,
                fis,
                total
            ).withCommonDownloadHeaders(target.name, etag, lastMod)
        }
    }

    private fun openInline(session: IHTTPSession): Response {
        val target =
            safeResolve(session.parameters["path"]?.firstOrNull() ?: return bad("missing path"))
                ?: return bad("invalid path")
        if (!target.exists() || !target.isFile) return notFound()
        val total = target.length()
        val mime = guessMime(target)
        val etag = etagOf(target)
        val lastMod = httpDate(target.lastModified())

        if (matchNotModified(session, etag, lastMod)) return notModified(
            etag,
            lastMod,
            privatePreview = true
        )
        if (session.method == Method.HEAD) return headResponse(
            mime,
            total,
            inline = true,
            etag = etag,
            lastModified = lastMod,
            privatePreview = true
        )

        val range = session.headers["range"]?.lowercase()
        val fis = FileInputStream(target)
        return if (range != null && range.startsWith("bytes=")) {
            val pair = parseRange(range, total)
            if (pair == null) newFixedLengthResponse(
                Response.Status.OK,
                mime,
                fis,
                total
            ).withInlineHeaders(etag, lastMod)
            else {
                val (start, end) = pair
                val len = (end - start + 1).coerceAtLeast(0)
                skipFully(fis, start)
                newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, boundedInput(fis, len), len)
                    .withRangeHeaders(start, end, total)
                    .withInlineHeaders(etag, lastMod)
            }
        } else {
            newFixedLengthResponse(Status.OK, mime, fis, total).withInlineHeaders(etag, lastMod)
        }
    }

    private fun upload(session: IHTTPSession): Response {
        val destDir = safeResolve(session.parameters["path"]?.firstOrNull() ?: rootDir)
            ?: return bad("invalid path")
        if (!destDir.exists()) destDir.mkdirs()

        val files = HashMap<String, String>()
        session.parseBody(files)
        val uploaded = mutableListOf<String>()

        session.parameters.forEach { (key, values) ->
            if (key.startsWith("file")) {
                values.forEach { rawName ->
                    val decoded = URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
                    val clean = sanitizedFileName(decoded)
                    val tmpPath = files[key] ?: return@forEach
                    val tmp = File(tmpPath)
                    val target = File(destDir, clean.ifBlank { tmp.name })
                    tmp.copyTo(target, overwrite = true)
                    uploaded += target.absolutePath
                    tmp.delete()
                }
            }
        }
        return ok("Uploaded:\n" + uploaded.joinToString("\n"))
    }

    private fun mkdir(session: IHTTPSession): Response {
        if (session.method == Method.POST) session.parseBody(HashMap())
        val base =
            safeResolve(session.parameters["path"]?.firstOrNull() ?: return bad("missing path"))
                ?: return bad("invalid path")
        val name = sanitizedFileName(
            session.parameters["name"]?.firstOrNull() ?: return bad("missing name")
        )
        val dir = File(base, name)
        if (!dir.exists()) dir.mkdirs()
        return ok("ok")
    }

    private fun delete(session: IHTTPSession): Response {
        if (session.method == Method.POST) session.parseBody(HashMap())
        val f = safeResolve(session.parameters["path"]?.firstOrNull() ?: return bad("missing path"))
            ?: return bad("invalid path")
        if (!f.exists()) return notFound()
        f.deleteRecursively()
        return ok("ok")
    }

    private fun rename(session: IHTTPSession): Response {
        if (session.method == Method.POST) session.parseBody(HashMap())
        val target = safeResolve(session.parameters["path"]?.firstOrNull() ?: return bad("missing path"))
            ?: return bad("invalid path")
        if (!target.exists()) return notFound()
        val nameParam = session.parameters["name"]?.firstOrNull() ?: return bad("missing name")
        val sanitized = sanitizedFileName(nameParam).trim()
        if (sanitized.isEmpty()) return bad("invalid name")
        if (target.name == sanitized) return ok("ok")
        val parent = target.parentFile ?: return bad("invalid target")
        val dest = File(parent, sanitized)
        val destSafe = safeResolve(dest.absolutePath) ?: return bad("invalid name")
        if (destSafe.exists()) return bad("target exists")

        val renamed = target.renameTo(destSafe)
        if (!renamed) {
            try {
                if (target.isDirectory) target.copyRecursively(destSafe, overwrite = true)
                else target.copyTo(destSafe, overwrite = true)
                target.deleteRecursively()
            } catch (e: Exception) {
                destSafe.deleteRecursively()
                return text(Status.INTERNAL_ERROR, "rename failed: ${e.message}")
            }
        }
        return ok("ok")
    }

    private fun move(session: IHTTPSession): Response {
        val src =
            safeResolve(session.parameters["from"]?.firstOrNull() ?: return bad("missing from"))
                ?: return bad("invalid from")
        val dst = safeResolve(session.parameters["to"]?.firstOrNull() ?: return bad("missing to"))
            ?: return bad("invalid to")
        if (!src.exists()) return notFound()
        val success = src.renameTo(dst)
        if (!success) {
            if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else src.copyTo(
                dst,
                overwrite = true
            )
            src.deleteRecursively()
        }
        return ok("ok")
    }

    private fun thumb(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return bad("path required")
        val w = session.parameters["w"]?.firstOrNull()?.toIntOrNull() ?: 256
        val h = session.parameters["h"]?.firstOrNull()?.toIntOrNull() ?: w
        val tMs = session.parameters["t"]?.firstOrNull()?.toLongOrNull() ?: 0L
        val f = safeResolve(path) ?: return bad("invalid path")
        if (!f.exists()) return notFound()
        return try {
            val data: ByteArray =
                if (isVideo(f.name)) genVideoFrameThumb(f, w, h, tMs) else genImageThumb(f, w, h)
            newFixedLengthResponse(
                Status.OK,
                "image/jpeg",
                data.inputStream(),
                data.size.toLong()
            ).apply {
                addHeader("Cache-Control", "public, max-age=604800")
            }
        } catch (e: Exception) {
            text(Status.INTERNAL_ERROR, "thumb error: ${e.message}")
        }
    }

    private fun zip(session: IHTTPSession): Response {
        if (session.method != Method.POST) return bad("POST only")
        val filesMap: MutableMap<String, String> = HashMap()
        session.parseBody(filesMap)
        val raw = session.parameters["paths"]?.firstOrNull() ?: session.parms["paths"]
        ?: filesMap["postData"] ?: ""
        val paths = parsePaths(raw)
        if (paths.isEmpty()) return bad("paths required")
        val filesToZip = paths.mapNotNull { safeResolve(it) }.filter { it.exists() }
        if (filesToZip.isEmpty()) return notFound()

        val pin = PipedInputStream()
        val pout = PipedOutputStream(pin)
        thread(name = "zip-stream") {
            ZipOutputStream(BufferedOutputStream(pout)).use { zos ->
                filesToZip.forEach { f ->
                    if (f.isDirectory) zipDir(zos, f, f.name + "/") else zipFile(zos, f, f.name)
                }
            }
        }
        return newChunkedResponse(Status.OK, "application/zip", pin).apply {
            addHeader("Content-Disposition", contentDispositionAttachment("pack.zip"))
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun parseRange(rangeHeader: String, total: Long): Pair<Long, Long>? {
        val spec = rangeHeader.removePrefix("bytes=").trim().split(',')[0].trim()
        val parts = spec.split('-', limit = 2)
        val sStr = parts.getOrNull(0)?.trim().orEmpty()
        val eStr = parts.getOrNull(1)?.trim().orEmpty()
        return when {
            sStr.isNotEmpty() && eStr.isNotEmpty() -> {
                val s = sStr.toLongOrNull() ?: return null
                val e = (eStr.toLongOrNull() ?: return null).coerceAtMost(total - 1)
                if (s > e) null else s to e
            }

            sStr.isNotEmpty() -> (sStr.toLongOrNull() ?: return null) to (total - 1)
            eStr.isNotEmpty() -> {
                val suf = eStr.toLongOrNull() ?: return null
                val e = total - 1
                val s = (total - suf).coerceAtLeast(0)
                s to e
            }

            else -> null
        }
    }

    private fun boundedInput(input: FileInputStream, limit: Long): InputStream =
        object : FilterInputStream(input) {
            var remaining = limit
            override fun read(): Int {
                if (remaining <= 0) return -1
                val r = super.read()
                if (r >= 0) remaining--
                return r
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val toRead = if (len.toLong() > remaining) remaining.toInt() else len
                val r = super.read(b, off, toRead)
                if (r > 0) remaining -= r
                return r
            }
        }

    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break else remaining--
            } else remaining -= skipped
        }
    }

    private fun headResponse(
        mime: String,
        total: Long,
        attachmentName: String? = null,
        etag: String? = null,
        lastModified: String? = null,
        inline: Boolean = false,
        privatePreview: Boolean = false
    ): Response {
        return newFixedLengthResponse(
            Status.OK,
            mime,
            ByteArrayInputStream(ByteArray(0)),
            0
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            if (etag != null) addHeader("ETag", etag)
            if (lastModified != null) addHeader("Last-Modified", lastModified)
            addHeader("Content-Length", total.toString())
            if (inline) addHeader(
                "Cache-Control",
                if (privatePreview) "private, max-age=0, no-store" else "public, max-age=0"
            )
            if (!inline && attachmentName != null) addHeader(
                "Content-Disposition",
                "attachment; filename=\"$attachmentName\""
            )
        }
    }

    private fun Response.withRangeHeaders(start: Long, end: Long, total: Long): Response = apply {
        addHeader("Content-Range", "bytes $start-$end/$total")
        addHeader("Accept-Ranges", "bytes")
    }

    // === Helpers: RFC 5987 Content-Disposition for correct UTF-8 filenames ===
    private fun contentDispositionAttachment(name: String): String {
        val asciiFallback = name.replace(Regex("[^\u0020-\u007E]"), "_")
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
    }

    private fun contentDispositionInline(name: String): String {
        val asciiFallback = name.replace(Regex("[^\u0020-\u007E]"), "_")
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "inline; filename=\"$asciiFallback\"; filename*=UTF-8''$encoded"
    }

    private fun Response.withCommonDownloadHeaders(
        name: String,
        etag: String?,
        lastModified: String?
    ): Response = apply {
        addHeader("Content-Disposition", contentDispositionAttachment(name))
        if (etag != null) addHeader("ETag", etag)
        if (lastModified != null) addHeader("Last-Modified", lastModified)
    }

    private fun Response.withInlineHeaders(etag: String?, lastModified: String?): Response = apply {
        addHeader("Accept-Ranges", "bytes")
        addHeader("Cache-Control", "private, max-age=0, no-store")
        if (etag != null) addHeader("ETag", etag)
        if (lastModified != null) addHeader("Last-Modified", lastModified)
    }

    private fun notModified(
        etag: String?,
        lastModified: String?,
        privatePreview: Boolean = false
    ): Response =
        newFixedLengthResponse(Status.NOT_MODIFIED, MIME_PLAINTEXT, "").apply {
            if (etag != null) addHeader("ETag", etag)
            if (lastModified != null) addHeader("Last-Modified", lastModified)
            addHeader(
                "Cache-Control",
                if (privatePreview) "private, max-age=0, no-store" else "public, max-age=0"
            )
        }

    private fun matchNotModified(
        session: IHTTPSession,
        etag: String,
        lastModified: String
    ): Boolean {
        val inm = session.headers["if-none-match"]
        val ims = session.headers["if-modified-since"]
        return (inm != null && inm == etag) || (ims != null && ims == lastModified)
    }

    private fun etagOf(f: File): String = "\"${f.length()}-${f.lastModified()}\""
    private fun httpDate(ts: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date(ts))

    private fun zipDir(zos: ZipOutputStream, dir: File, base: String) {
        val kids = dir.listFiles() ?: return
        if (kids.isEmpty()) {
            zos.putNextEntry(ZipEntry(base)); zos.closeEntry(); return
        }
        for (c in kids) {
            val entry = base + c.name + if (c.isDirectory) "/" else ""
            if (c.isDirectory) zipDir(zos, c, entry) else zipFile(zos, c, entry)
        }
    }

    private fun zipFile(zos: ZipOutputStream, f: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(f).use { it.copyTo(zos, 8 * 1024) }
        zos.closeEntry()
    }

    private fun parsePaths(raw: String): List<String> = try {
        val t = raw.trim()
        when {
            t.isEmpty() -> emptyList()
            t.startsWith("[") -> {
                val arr = JSONArray(t)
                (0 until arr.length()).map { arr.getString(it) }
            }

            t.contains(",") -> t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> listOf(t)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun genImageThumb(f: File, w: Int, h: Int): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > w * 2 || opts.outHeight / sample > h * 2) sample *= 2
        val src = BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("decode image failed")
        val scaled = src.scale(w, (src.height * (w.toFloat() / src.width)).toInt())
        if (scaled !== src) src.recycle()
        return ByteArrayOutputStream().use { bos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            scaled.recycle()
            bos.toByteArray()
        }
    }

    private fun genVideoFrameThumb(f: File, w: Int, h: Int, tMs: Long): ByteArray {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(f.absolutePath)
        val timeUs = tMs * 1000
        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(-1) ?: throw IllegalStateException("get frame failed")
        val scaled = frame.scale(w, (frame.height * (w.toFloat() / frame.width)).toInt())
        if (scaled !== frame) frame.recycle()
        return ByteArrayOutputStream().use { bos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            scaled.recycle()
            retriever.release()
            bos.toByteArray()
        }
    }

    private fun safeResolve(raw: String): File? {
        /*val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        val f = File(decoded)
        val root = File(rootDir)
        val canon = f.canonicalFile
        return if (canon.path.startsWith(root.canonicalPath)) canon else null*/

        val rootCanon = File(rootDir).canonicalFile
        val canon = File(raw).canonicalFile
        return if (canon.path.startsWith(rootCanon.path)) canon else null
    }

    private fun sanitizedFileName(name: String): String = name
        .replace("\\", "/")
        .substringAfterLast('/')
        .replace(Regex("[\\r\\n\\t]"), " ")
        .take(255)

    private fun ok(msg: String) = newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, msg)
    private fun bad(msg: String) = newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, msg)
    private fun jsonOk(body: String) =
        newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", body)

    private fun text(status: Status, body: String) =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun notFound() = newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    private fun guessMime(file: File): String =
        URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
}

// Helpers referenced in thumb()
private fun isVideo(name: String): Boolean {
    val lower = name.lowercase(Locale.getDefault())
    return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(
        ".webm"
    ) || lower.endsWith(".avi")
}
