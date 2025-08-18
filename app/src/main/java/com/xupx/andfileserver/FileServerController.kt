package com.xupx.andfileserver

import com.xupx.andfileserver.server.FileHttpServer
import fi.iki.elonen.NanoHTTPD

object FileServerController {

    private val server: NanoHTTPD = FileHttpServer(
        context = FsApplication.application,
        webDir = Config.WEB_DIR,
        rootDir = Config.ROOT_DIR,
        port = Config.SERVER_PORT
    )

    private var isRunning = false

    fun startFileServer() {
        if (isRunning) {
            return
        }
        isRunning = true
        server.start(Config.READ_TIME_OUT)
    }

    fun stopFileServer() {
        if (!isRunning) {
            return
        }
        isRunning = false
        server.stop()
    }

    fun isServerRunning(): Boolean {
        return isRunning && server.isAlive
    }
}
