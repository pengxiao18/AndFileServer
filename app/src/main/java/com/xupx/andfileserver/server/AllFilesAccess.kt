package com.xupx.andfileserver.server

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

object AllFilesAccess {

    /** 是否已具备整盘访问（Android 11+ 有效）*/
    fun hasAllFilesAccess(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                android.os.Environment.isExternalStorageManager()
    }

    /** 引导到系统设置页开权限（用户必须手动开关） */
    fun requestAllFilesAccess(activity: Activity) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            // 尝试直接跳到本应用的“所有文件访问”设置页
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = "package:${activity.packageName}".toUri()
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            // 某些系统只支持总开关页，再让用户进入后自行选择本应用
            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

}
