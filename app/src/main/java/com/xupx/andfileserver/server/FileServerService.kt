package com.xupx.andfileserver.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xupx.andfileserver.Config
import com.xupx.andfileserver.FileServerController
import com.xupx.andfileserver.R
import com.xupx.andfileserver.utils.Utils

class FileServerService : Service() {

    companion object {
        private var startServiceIntent: Intent? = null

        fun startService(context: Context) {
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

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        FileServerController.startFileServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        FileServerController.stopFileServer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "file_server_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "File Server",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val ipAddress = Utils.getDeviceIpAddress() ?: ""
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("文件服务已开启")
            .setContentText("在同一局域网用浏览器访问\nhttp://${ipAddress}:${Config.SERVER_PORT}")
            .setOngoing(true)
            .setAutoCancel(false)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
