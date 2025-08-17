package com.xupx.andfileserver

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.xupx.andfileserver.server.AllFilesAccess
import com.xupx.andfileserver.server.FileServerService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureAllFilesThenStart()
    }

    private fun ensureAllFilesThenStart() {
        when {
            // Android 11+ 走 All files
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R -> {
                if (AllFilesAccess.hasAllFilesAccess()) {
                    onStorageReady()
                } else {
                    AllFilesAccess.requestAllFilesAccess(this)
                    Toast.makeText(this, "请在系统设置中授予“对所有文件的访问”", Toast.LENGTH_SHORT)
                        .show()
                    // 返回本界面后在 onResume 再次检查
                }
            }
            // Android 10（Q）：尽量 targetSdk=29 并设置 requestLegacyExternalStorage=true，可直用 File API
            android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q -> {
                onStorageReady()
            }
            // Android 9 及以下：普通运行时权限
            else -> {
                requestLegacyPerms()
            }
        }
    }

    private fun requestLegacyPerms() {
        val perms = arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val need = perms.any {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }
        if (need) {
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { granted ->
                if (granted.values.all { it }) onStorageReady()
                else Toast.makeText(this, "未授予存储权限", Toast.LENGTH_SHORT).show()
            }.launch(perms)
        } else onStorageReady()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回后再次确认
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            AllFilesAccess.hasAllFilesAccess()
        ) {
            onStorageReady()
        }
    }

    private var isReady = false
    private fun onStorageReady() {
        if (isReady) return
        isReady = true
        // ✅ 在这里开启你的文件服务 / 执行整盘读写逻辑（NanoHTTPD 等）
        FileServerService.startService(this.application)
        Toast.makeText(this, "已具备整盘访问，服务启动", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        FileServerService.stopService(this.application)
        super.onDestroy()
    }

}
