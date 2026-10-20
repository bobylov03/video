package com.example.videosync.data

import android.content.Context

/**
 * Простое хранилище настроек и состояния на SharedPreferences:
 *  - адрес сервера
 *  - токен авторизации и имя пользователя
 *  - множество ID видео из галереи, которые уже загружены (чтобы не слать дубли)
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("video_sync", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var username: String?
        get() = sp.getString("username", null)
        set(v) = sp.edit().putString("username", v).apply()

    val isLoggedIn: Boolean get() = !token.isNullOrEmpty() && serverUrl.isNotEmpty()

    /** ID видео MediaStore, которые уже отправлены на сервер. */
    val uploadedIds: MutableSet<String>
        get() = sp.getStringSet("uploaded_ids", emptySet())!!.toMutableSet()

    fun markUploaded(mediaId: String) {
        val s = uploadedIds
        s.add(mediaId)
        sp.edit().putStringSet("uploaded_ids", s).apply()
    }

    fun clearSession() {
        sp.edit().remove("token").remove("username").apply()
    }
}
