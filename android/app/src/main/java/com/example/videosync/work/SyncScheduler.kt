package com.example.videosync.work

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Планирование фоновой синхронизации.
 *
 *  - Периодическая задача каждые 15 минут (минимум, который разрешает Android)
 *    проверяет галерею и догружает новые видео.
 *  - Одноразовая задача запускается сразу (например, при открытии приложения).
 *
 * Условие сети: CONNECTED — грузим при любом интернете.
 * Если хотите экономить мобильный трафик и грузить только по Wi-Fi —
 * поменяйте на NetworkType.UNMETERED.
 */
@UnstableApi
object SyncScheduler {

    private const val PERIODIC = "video_sync_periodic"
    private const val ONESHOT = "video_sync_now"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)   // ← Wi-Fi only: UNMETERED
        .build()

    /** Включить постоянную фоновую синхронизацию. */
    fun enablePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /** Запустить проверку прямо сейчас (если есть сеть). */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT, ExistingWorkPolicy.REPLACE, request
        )
    }

    /** Полностью выключить синхронизацию. */
    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(ONESHOT)
    }
}
