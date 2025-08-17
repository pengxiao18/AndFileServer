package com.xupx.andfileserver.server

import java.util.Locale

private const val KB: Long = 1024
private const val MB: Long = KB * 1024
private const val GB: Long = MB * 1024

/** 将字节数格式化为人类可读的字符串 */
fun Long.formatSize(): String {

    if (this < 0) return "-"
    fun oneDec(v: Double) = String.format(Locale.US, "%.1f", v).removeSuffix(".0")

    return when {
        this >= GB -> oneDec(this.toDouble() / GB) + "G"
        this >= MB -> oneDec(this.toDouble() / MB) + "M"
        this >= KB -> oneDec(this.toDouble() / KB) + "K"
        else -> "${this}B"
    }
}
