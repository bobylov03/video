package com.example.videosync.net

import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.FormBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Все обращения к серверу. Методы синхронные (блокирующие) — их вызывают
 * из фонового потока (Worker) или из корутины с Dispatchers.IO.
 */
class Api(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)   // видео может грузиться долго
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class AuthResult(val token: String, val username: String)

    /** Регистрация. Возвращает токен или бросает IllegalStateException с текстом ошибки. */
    fun register(username: String, password: String): AuthResult =
        auth("/api/register", username, password)

    /** Вход. */
    fun login(username: String, password: String): AuthResult =
        auth("/api/login", username, password)

    private fun auth(path: String, username: String, password: String): AuthResult {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()
        val req = Request.Builder().url(baseUrl + path).post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw IllegalStateException(detail?.ifEmpty { "Ошибка ${resp.code}" } ?: "Ошибка ${resp.code}")
            }
            val json = JSONObject(text)
            return AuthResult(json.getString("token"), json.getString("username"))
        }
    }

    /**
     * Загрузка одного видеофайла на сервер.
     * @return true при успехе.
     */
    fun uploadVideo(token: String, file: File, origName: String): Boolean {
        val media = (file.name.substringAfterLast('.', "mp4").let { "video/$it" })
            .toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("orig_name", origName)
            .addFormDataPart("file", file.name, file.asRequestBody(media))
            .build()

        val req = Request.Builder()
            .url("$baseUrl/api/upload")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            return resp.isSuccessful
        }
    }
}
