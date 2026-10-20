package com.example.videosync.work

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.videosync.data.Prefs
import com.example.videosync.net.Api
import com.example.videosync.net.VideoCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Основная фоновая задача.
 * 1) Находит в галерее видео, которых ещё нет в списке загруженных.
 * 2) Каждое сжимает в 480p во временный файл.
 * 3) Отправляет на сервер.
 * 4) Помечает как загруженное и удаляет временный файл.
 *
 * WorkManager запускает эту задачу только при наличии сети (constraint задан
 * при планировании в MainActivity) и сам повторяет её при сбое — поэтому
 * видео, снятые без интернета, дождутся связи и уйдут автоматически.
 */
@UnstableApi
class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        val token = prefs.token
        val server = prefs.serverUrl
        if (token.isNullOrEmpty() || server.isEmpty()) {
            return@withContext Result.success() // не настроено — ничего не делаем
        }

        val api = Api(server)
        val pending = findNewVideos(prefs.uploadedIds)
        if (pending.isEmpty()) return@withContext Result.success()

        var hadFailure = false

        for (v in pending) {
            val tmp = File(applicationContext.cacheDir, "compressed_${v.id}.mp4")
            var toUpload = tmp
            try {
                // --- сжатие в 480p ---
                try {
                    VideoCompressor.compressTo480p(applicationContext, v.uri, tmp)
                } catch (e: Throwable) {
                    // если сжать не удалось — как запасной вариант грузим оригинал
                    toUpload = copyOriginalToCache(v)
                }

                // --- отправка на сервер ---
                val ok = api.uploadVideo(token, toUpload, v.displayName)
                if (ok) {
                    prefs.markUploaded(v.id)
                } else {
                    hadFailure = true
                }
            } catch (e: Throwable) {
                hadFailure = true // например, оборвалась связь
            } finally {
                tmp.delete()
                if (toUpload != tmp) toUpload.delete()
            }
        }

        // если что-то не отправилось — просим WorkManager повторить позже
        if (hadFailure) Result.retry() else Result.success()
    }

    private data class VideoItem(
        val id: String,
        val uri: android.net.Uri,
        val displayName: String
    )

    /** Ищет видео в MediaStore, которых ещё нет в множестве уже загруженных. */
    private fun findNewVideos(alreadyUploaded: Set<String>): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        applicationContext.contentResolver.query(
            collection, projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol).toString()
                if (id in alreadyUploaded) continue
                val uri = ContentUris.withAppendedId(collection, id.toLong())
                val name = cursor.getString(nameCol) ?: "video_$id.mp4"
                result.add(VideoItem(id, uri, name))
            }
        }
        return result
    }

    /** Запасной вариант: скопировать оригинал в кэш, если сжатие не удалось. */
    private fun copyOriginalToCache(v: VideoItem): File {
        val out = File(applicationContext.cacheDir, "orig_${v.id}.mp4")
        applicationContext.contentResolver.openInputStream(v.uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }
}
