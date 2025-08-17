package com.xupx.andfileserver

import fi.iki.elonen.NanoHTTPD

object Config {

    const val ROOT_DIR = "/sdcard"
    const val SERVER_PORT = 8080
    const val READ_TIME_OUT = NanoHTTPD.SOCKET_READ_TIMEOUT
    const val WEB_DIR = "website/"

}
