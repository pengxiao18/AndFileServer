package com.xupx.andfileserver.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import com.xupx.andfileserver.utils.Utils
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread


class FileHttpServer(
    private val context: Context,
    private val webDir: String = "",
    private val rootDir: String = "/sdcard",
    port: Int,
    // private val token: String // 简单鉴权用
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        // val method = session.method

        // 静态资源允许匿名访问首页（注入 token），其他 API 需要 X-Token
        /*val isStatic =
            uri == "/" || uri.endsWith(".html") || uri.endsWith(".css") || uri.endsWith(".js")
        if (!isStatic) {
            val ok = session.headers["x-token"] == token
            if (!ok) return text(Response.Status.UNAUTHORIZED, "Unauthorized")
        }*/

        return try {
            when {
                uri == "/" -> serveIndex()
                uri.endsWith(".html") -> serveHtml(uri.trimStart('/'))
                uri.endsWith(".css") -> serveAsset(uri.trimStart('/'), "text/css")
                uri.endsWith(".js") -> serveAsset(uri.trimStart('/'), "application/javascript")
                uri.startsWith("/ls") -> listDir(session)
                uri.startsWith("/dl") -> download(session)
                uri.startsWith("/upload") -> upload(session)
                uri.startsWith("/mkdir") -> mkdir(session)
                uri.startsWith("/rm") -> delete(session)
                uri.startsWith("/mv") -> move(session)
                uri.startsWith("/thumb") -> thumb(session)
                uri.startsWith("/zip") -> zip(session)
                uri.startsWith("/open") -> openInline(session)
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        } catch (e: Exception) {
            text(Response.Status.INTERNAL_ERROR, "Error: ${e.message}")
        }
    }

    private fun serveIndex(): Response {
        return serveHtml("index.html")
    }

    private fun serveHtml(
        name: String
    ): Response {
        return serveAsset(name, "text/html") { text ->
            text.replace("{{rootDir}}", rootDir)
        }
    }

    private fun serveAsset(
        name: String,
        mime: String,
        block: ((text: String) -> String)? = null
    ): Response {
        return try {
            val filePath = if (!webDir.endsWith("/") && !name.startsWith("/")) {
                "$webDir/$name"
            } else {
                "$webDir$name"
            }
            val txt = loadTextAsset(filePath)
            val handleTxt = block?.invoke(txt) ?: txt
            newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", handleTxt)
        } catch (_: Exception) {
            text(Response.Status.NOT_FOUND, "Asset not found: $name")
        }
    }

    private fun loadTextAsset(name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun listDir(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: rootDir
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not a directory"
            )
        }
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
        val json = JSONArray(arr).toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun download(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return bad("missing path")
        val file = File(path)
        if (!file.exists() || !file.isFile) return notFound()

        // 支持 Range（断点续传）
        val total = file.length()
        val rangeHeader = session.headers["range"]
        val input = FileInputStream(file)
        return if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.removePrefix("bytes=").split("-")
            val start = range[0].toLong()
            val end = (range.getOrNull(1)?.toLong()) ?: (total - 1)
            input.skip(start)
            newChunkedResponse(Response.Status.PARTIAL_CONTENT, guessMime(file), input).apply {
                addHeader("Content-Range", "bytes $start-$end/$total")
                addHeader("Accept-Ranges", "bytes")
            }
        } else {
            newChunkedResponse(Response.Status.OK, guessMime(file), input).apply {
                addHeader("Content-Length", "$total")
                addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            }
        }
    }

    private fun upload(session: IHTTPSession): Response {
        val destDirPath =
            session.parameters["path"]?.firstOrNull() ?: rootDir /*"/sdcard/Download"*/
        val destDir = File(destDirPath)
        if (!destDir.exists()) destDir.mkdirs()

        val files = HashMap<String, String>()
        session.parseBody(files) // NanoHTTPD 会把上传的临时文件路径填到 files["file"]
        // 对于多文件，字段名可能是同名多次出现
        val uploaded = mutableListOf<String>()

        // 遍历所有参数，找到 file 字段
        session.parameters.forEach { (key, values) ->
            if (key.startsWith("file")) {
                values.forEach { fileName ->
                    val decodeFileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8.name())
                    val tmpPath = files[key] ?: return@forEach
                    val tmp = File(tmpPath)
                    val target = File(
                        destDir,
                        decodeFileName ?: tmp.name
                    ) // 取原名；某些前端库会放在 file
                    tmp.copyTo(target, overwrite = true)
                    uploaded += target.absolutePath
                    tmp.delete()
                }
            }
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_PLAINTEXT,
            "Uploaded:\n${uploaded.joinToString("\n")}"
        )
    }

    private fun mkdir(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            val files = HashMap<String, String>()
            session.parseBody(files)  // 必须先解析
        }
        val base = session.parameters["path"]?.firstOrNull() ?: return bad("missing path")
        val name = session.parameters["name"]?.firstOrNull() ?: return bad("missing name")
        val dir = File(base, name)
        if (!dir.exists()) dir.mkdirs()
        return ok("ok")
    }

    private fun delete(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            val files = HashMap<String, String>()
            session.parseBody(files)  // 必须先解析
        }
        val path = session.parameters["path"]?.firstOrNull() ?: return bad("missing path")
        val f = File(path)
        if (!f.exists()) return notFound()
        f.deleteRecursively()
        return ok("ok")
    }

    private fun move(session: IHTTPSession): Response {
        val from = session.parameters["from"]?.firstOrNull() ?: return bad("missing from")
        val to = session.parameters["to"]?.firstOrNull() ?: return bad("missing to")
        val src = File(from)
        val dst = File(to)
        if (!src.exists()) return notFound()
        if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else src.copyTo(
            dst,
            overwrite = true
        )
        src.deleteRecursively()
        return ok("ok")
    }

    private fun thumb(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return bad("path required")
        val w = session.parameters["w"]?.firstOrNull()?.toIntOrNull() ?: 256
        val h = session.parameters["h"]?.firstOrNull()?.toIntOrNull() ?: w
        val tMs = session.parameters["t"]?.firstOrNull()?.toLongOrNull() ?: 0L  // 视频取帧时间（毫秒）

        val f = safeResolve(path) ?: return bad("invalid path")
        if (!f.exists()) return notFound()

        return try {
            val data: ByteArray = if (isVideo(f.name)) {
                genVideoFrameThumb(f, w, h, tMs)
            } else {
                genImageThumb(f, w, h)
            }
            val resp = newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                data.inputStream(),
                data.size.toLong()
            )
            resp.addHeader("Cache-Control", "public, max-age=604800")
            resp
        } catch (e: Exception) {
            text(Response.Status.INTERNAL_ERROR, "thumb error: ${e.message}")
        }
    }

    private fun zip(session: IHTTPSession): Response {
        if (session.method != Method.POST) return bad("POST only")
        // 兼容 paths=以逗号分隔 或 JSON 数组
        /*val raw = session.parameters["paths"]?.firstOrNull() ?: run {
            // NanoHTTPD 解析 body：
            val files: MutableMap<String, String> = HashMap()
            session.parseBody(files)  // 将 body 放入 session.parms["postData"]
            session.parms["postData"] ?: ""
        }*/

        val files: MutableMap<String, String> = HashMap()
        session.parseBody(files)
        val raw = session.parameters["paths"]?.firstOrNull()
            ?: session.parms["paths"]
            ?: files["postData"]
            ?: ""

        val paths: List<String> = parsePaths(raw)
        if (paths.isEmpty()) return bad("paths required")

        val filesToZip = paths.mapNotNull { safeResolve(it) }.filter { it.exists() }
        if (filesToZip.isEmpty()) return notFound()

        val pin = PipedInputStream()
        val pout = PipedOutputStream(pin)

        thread(name = "zip-stream") {
            ZipOutputStream(BufferedOutputStream(pout)).use { zos ->
                filesToZip.forEach { f ->
                    if (f.isDirectory) {
                        zipDir(zos, f, f.name + "/")
                    } else {
                        zipFile(zos, f, f.name)
                    }
                }
            }
        }

        val resp = newChunkedResponse(Response.Status.OK, "application/zip", pin)
        resp.addHeader("Content-Disposition", "attachment; filename=\"pack.zip\"")
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun parseRange(rangeHeader: String, total: Long): Pair<Long, Long>? {
        // 仅取第一个范围（多段范围可以按需扩展）
        val spec = rangeHeader.removePrefix("bytes=").trim().split(",")[0].trim()
        val parts = spec.split("-", limit = 2)
        val startStr = parts.getOrNull(0)?.trim().orEmpty()
        val endStr = parts.getOrNull(1)?.trim().orEmpty()

        return when {
            // 形式：bytes=Start-End
            startStr.isNotEmpty() && endStr.isNotEmpty() -> {
                val s = startStr.toLongOrNull() ?: return null
                val e = (endStr.toLongOrNull() ?: return null).coerceAtMost(total - 1)
                if (s > e) null else (s to e)
            }
            // 形式：bytes=Start-
            startStr.isNotEmpty() -> {
                val s = startStr.toLongOrNull() ?: return null
                s to (total - 1)
            }
            // 形式：bytes=-SuffixLen
            endStr.isNotEmpty() -> {
                val suffix = endStr.toLongOrNull() ?: return null
                val e = total - 1
                val s = (total - suffix).coerceAtLeast(0)
                s to e
            }

            else -> null
        }
    }

    private fun openInline(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull() ?: return bad("missing path")
        val file = File(path)
        if (!file.exists() || !file.isFile) return notFound()

        val total = file.length()
        val rangeHeader = session.headers["range"]?.lowercase()
        val mime = guessMime(file) // 用你的 MIME 推断函数

        return if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = parseRange(rangeHeader, total)
            if (range == null) {
                // Range 无法解析，返回 200 全量
                val input = FileInputStream(file)
                newChunkedResponse(Response.Status.OK, mime, input).apply {
                    addHeader("Content-Length", "$total")
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Cache-Control", "private, max-age=0, no-store")
                }
            } else {
                val (start, end) = range
                val len = (end - start + 1).coerceAtLeast(0)
                val input = FileInputStream(file).apply { skip(start) }
                newChunkedResponse(Response.Status.PARTIAL_CONTENT, mime, input).apply {
                    addHeader("Content-Range", "bytes $start-$end/$total")
                    addHeader("Content-Length", "$len")
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Cache-Control", "private, max-age=0, no-store")
                }
            }
        } else {
            // 无 Range：返回 200 全量（不设置 attachment，供 <img>/<video> 预览）
            val input = FileInputStream(file)
            newChunkedResponse(Response.Status.OK, mime, input).apply {
                addHeader("Content-Length", "$total")
                addHeader("Accept-Ranges", "bytes")
                addHeader("Cache-Control", "private, max-age=0, no-store")
            }
        }
    }

    private fun zipDir(zos: ZipOutputStream, dir: File, base: String) {
        val kids = dir.listFiles() ?: return
        if (kids.isEmpty()) {
            zos.putNextEntry(ZipEntry(base))
            zos.closeEntry()
            return
        }
        for (c in kids) {
            val entryName = base + c.name + if (c.isDirectory) "/" else ""
            if (c.isDirectory) zipDir(zos, c, entryName) else zipFile(zos, c, entryName)
        }
    }

    private fun zipFile(zos: ZipOutputStream, f: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(f).use { input ->
            input.copyTo(zos, 8 * 1024)
        }
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

            t.contains(",") -> t.split(",").map { it.trim() }.filter { it.isNotEmpty() }
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
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeFile(f.absolutePath, opts2)
            ?: throw IllegalStateException("decode image failed")
        val scaled = src.scale(w, (src.height * (w.toFloat() / src.width)).toInt())
        if (scaled !== src) src.recycle()
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        scaled.recycle()
        return bos.toByteArray()
    }

    private fun genVideoFrameThumb(f: File, w: Int, h: Int, tMs: Long): ByteArray {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(f.absolutePath)
        val timeUs = tMs * 1000
        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(-1) ?: throw IllegalStateException("get frame failed")
        val scaled = frame.scale(w, (frame.height * (w.toFloat() / frame.width)).toInt())
        if (scaled !== frame) frame.recycle()
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        scaled.recycle()
        retriever.release()
        return bos.toByteArray()
    }

    // 工具：限制访问根目录（请替换 rootDir 为你项目中的根目录 File 对象/路径）
    private fun safeResolve(raw: String): File? {
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        val f = File(decoded)
        val root = File(rootDir) // 如果你已有 rootDir 变量，请替换这一行
        val canon = f.canonicalFile
        return if (canon.path.startsWith(root.canonicalPath)) canon else null
    }

    private fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return listOf("mp4", "mkv", "avi", "mov", "wmv", "webm").contains(ext)
    }

    private fun ok(msg: String) =
        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, msg)

    private fun bad(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)

    private fun text(status: Response.Status, body: String) =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

    private fun guessMime(file: File): String =
        URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
}