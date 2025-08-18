package com.xupx.andfileserver

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.xupx.andfileserver.databinding.MainLayoutBinding
import com.xupx.andfileserver.server.AllFilesAccess
import com.xupx.andfileserver.server.FileServerService
import com.xupx.andfileserver.utils.Utils

class MainActivity : ComponentActivity() {
    private lateinit var binding: MainLayoutBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            ensureAllFilesThenStart()
        } else {
            Toast.makeText(
                application, "请授予通知权限", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val diskPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            onStorageReady()
        } else {
            Toast.makeText(this, "未授予存储权限，服务启动失败", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnStart.setOnClickListener {
            if (isStarted) {
                stopService()
            } else {
                requestNotificationPermission()
            }
        }

        onBackPressedDispatcher.addCallback(
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isStarted) {
                        moveTaskToBack(true)
                    } else {
                        finish()
                    }
                }
            })

        isStarted = FileServerController.isServerRunning()
        refreshUI()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshUI() {
        if (!isStarted) {
            binding.btnStart.text = binding.root.resources.getString(R.string.btn_start)
            binding.tvIp.text = ""
        } else {
            binding.btnStart.text = binding.root.resources.getString(R.string.btn_stop)
            Utils.getDeviceIpAddress()?.let {
                binding.tvIp.text = "http://$it:${Config.SERVER_PORT}"
            }
        }
    }

    /**
     * 通知权限
     */
    private fun requestNotificationPermission() {
        // Android 13 以上才需要申请
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 检查权限是否已经授予
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // 未授予，请求权限
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // 已经授予
                ensureAllFilesThenStart()
            }
        }
    }

    /**
     * 文件权限
     */
    private fun ensureAllFilesThenStart() {
        when {
            // Android 11+ 走 All files
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (AllFilesAccess.hasAllFilesAccess()) {
                    onStorageReady()
                } else {
                    AllFilesAccess.requestAllFilesAccess(this)
                    Toast.makeText(
                        this, "请在系统设置中授予“对所有文件的访问”", Toast.LENGTH_SHORT
                    ).show()
                }
            }
            // Android 9 及以下：普通运行时权限
            else -> {
                requestLegacyPerms()
            }
        }
    }

    private fun requestLegacyPerms() {
        val perms = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val need = perms.any {
            ContextCompat.checkSelfPermission(this, it) !=
                    PackageManager.PERMISSION_GRANTED
        }
        if (need) {
            diskPermissionLauncher.launch(perms)
        } else onStorageReady()
    }

    private var isStarted = false

    @SuppressLint("SetTextI18n")
    private fun onStorageReady() {
        if (isStarted) return
        isStarted = true
        // ✅ 在这里开启你的文件服务 / 执行整盘读写逻辑（NanoHTTPD 等）
        FileServerService.startService(this.application)
        refreshUI()
        Toast.makeText(
            this, "服务已启动", Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopService() {
        isStarted = false
        FileServerService.stopService(application)
        refreshUI()
    }

    override fun onDestroy() {
        FileServerService.stopService(this.application)
        super.onDestroy()
    }
}
