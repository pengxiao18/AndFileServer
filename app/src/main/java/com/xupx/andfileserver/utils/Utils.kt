package com.xupx.andfileserver.utils

import android.annotation.SuppressLint
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Locale

object Utils {

    fun getDeviceIpAddress(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        interfaces?.toList()?.forEach { networkInterface ->
            // 过滤掉未启用、Loopback、虚拟等接口
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                return@forEach
            }
            networkInterface.inetAddresses?.toList()?.forEach { inetAddress ->
                // 只关心 IPv4 且不是回环地址
                if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                    return inetAddress.hostAddress
                }
            }
        }
        return null
    }

    @SuppressLint("ConstantLocale")
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    fun formatTimestamp(ts: Long): String {
        return sdf.format(ts)
    }

}