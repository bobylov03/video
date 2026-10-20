package com.example.videosync.net

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Сжимает видео до высоты 480p с помощью Media3 Transformer.
 * Оригинал в галерее НЕ меняется — создаётся новый файл во временной папке.
 *
 * Метод блокирующий: его вызывают из фонового потока (Worker). Transformer
 * требует поток с Looper, поэтому вся работа запускается на главном потоке,
 * а фоновый поток ждёт завершения через CountDownLatch.
 */
@UnstableApi
object VideoCompressor {

    private const val TARGET_HEIGHT = 480   // 480p
    private const val TARGET_BITRATE = 1_500_000 // ~1.5 Мбит/с

    /**
     * @return файл со сжатым видео, либо бросает исключение при ошибке.
     */
    fun compressTo480p(context: Context, input: Uri, outputFile: File): File {
        val latch = CountDownLatch(1)
        var error: Throwable? = null

        Handler(Looper.getMainLooper()).post {
            try {
                val mediaItem = MediaItem.fromUri(input)

                // Эффект: привести видео к высоте 480, ширина — пропорционально
                val presentation = Presentation.createForHeight(TARGET_HEIGHT)

                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(/* audio */ emptyList(), /* video */ listOf(presentation)))
                    .build()

                // Кодировщик с ограничением битрейта — чтобы файл был лёгким
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(TARGET_BITRATE)
                            .build()
                    )
                    .build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            latch.countDown()
                        }
                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            error = exception
                            latch.countDown()
                        }
                    })
                    .build()

                transformer.start(edited, outputFile.absolutePath)
            } catch (t: Throwable) {
                error = t
                latch.countDown()
            }
        }

        latch.await() // ждём завершения кодирования
        error?.let { throw it }
        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException("Сжатие не дало файла")
        }
        return outputFile
    }
}
