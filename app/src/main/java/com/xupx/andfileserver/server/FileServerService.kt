package com.xupx.andfileserver.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xupx.andfileserver.R
import fi.iki.elonen.NanoHTTPD

class FileServerService : Service() {

    companion object {
        private var startServiceIntent: Intent? = null

        fun startService(context: Context) {
            // 检查是否有通知权限，没有的话直接返回
            /*NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            if (!manager.areNotificationsEnabled()) {
                return;
            }*/
            val intent = Intent(context, FileServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            startServiceIntent = intent
        }

        /**
         * 关闭服务
         */
        fun stopService(context: Context) {
            if (startServiceIntent != null) {
                context.stopService(startServiceIntent)
            }
            startServiceIntent = null
        }
    }

    private var server: NanoHTTPD? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        server = FileHttpServer(
            context = application,
            webDir = "website/",
            rootDir = "/sdcard",
            port = 8080
        ) // 自定义类，见下
        server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "file_server_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(channelId, "File Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("文件服务已开启")
            .setContentText("在同一局域网用浏览器访问：http://<手机IP>:8080")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
