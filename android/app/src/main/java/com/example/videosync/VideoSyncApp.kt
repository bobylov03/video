package com.example.videosync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class VideoSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Канал уведомлений для фоновой синхронизации (нужно на Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Синхронизация видео",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Фоновая отправка видео на сервер"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_ID = "video_sync_channel"
    }
}
