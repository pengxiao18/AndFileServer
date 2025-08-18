package com.xupx.andfileserver.server

import android.content.Context
import com.xupx.andfileserver.utils.Utils
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets


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
        val destDirPath = session.parameters["path"]?.firstOrNull() ?: rootDir /*"/sdcard/Download"*/
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