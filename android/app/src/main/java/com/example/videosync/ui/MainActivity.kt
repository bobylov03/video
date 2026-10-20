package com.example.videosync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.example.videosync.data.Prefs
import com.example.videosync.net.Api
import com.example.videosync.work.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * Единственный экран приложения.
 *  - Если пользователь не вошёл: поля "адрес сервера / логин / пароль" + кнопки
 *    "Войти" и "Зарегистрироваться".
 *  - Если вошёл: статус, кнопки "Проверить сейчас" и "Выйти".
 *
 * UI собран кодом (без XML-разметки), чтобы проект был компактным.
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout

    // запрос разрешения на чтение видео
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        setContentView(root)

        requestNeededPermissions()
        render()
    }

    // ----------------------------------------------------------------------
    private fun render() {
        root.removeAllViews()
        title("🎬 Video Sync")

        if (!prefs.isLoggedIn) renderLogin() else renderLoggedIn()
    }

    // --- экран входа / регистрации ---
    private fun renderLogin() {
        val serverField = input("Адрес сервера, напр. http://192.168.1.50:8000", prefs.serverUrl)
        val userField = input("Логин")
        val passField = input("Пароль", isPassword = true)

        root.addView(serverField)
        root.addView(userField)
        root.addView(passField)

        val status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)

        fun doAuth(isRegister: Boolean) {
            val server = serverField.text.toString().trim().trimEnd('/')
            val u = userField.text.toString().trim()
            val p = passField.text.toString()
            if (server.isEmpty() || u.isEmpty() || p.isEmpty()) {
                status.text = "Заполните все поля"; return
            }
            prefs.serverUrl = server
            status.text = "Подождите..."
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        val api = Api(server)
                        if (isRegister) api.register(u, p) else api.login(u, p)
                    }
                }
                res.onSuccess {
                    prefs.token = it.token
                    prefs.username = it.username
                    SyncScheduler.enablePeriodic(this@MainActivity)
                    SyncScheduler.runNow(this@MainActivity)
                    render()
                }.onFailure {
                    status.text = "Ошибка: ${it.message}"
                }
            }
        }

        root.addView(button("Войти") { doAuth(false) })
        root.addView(button("Зарегистрироваться") { doAuth(true) })
    }

    // --- экран после входа ---
    private fun renderLoggedIn() {
        val hasPerm = hasVideoPermission()

        root.addView(TextView(this).apply {
            text = "Вы вошли как: ${prefs.username}\nСервер: ${prefs.serverUrl}"
            setPadding(0, dp(8), 0, dp(16))
        })

        root.addView(TextView(this).apply {
            text = if (hasPerm)
                "✅ Доступ к видео есть.\nНовые видео из галереи автоматически сжимаются в 480p и отправляются на сервер (каждые ~15 минут и при открытии приложения). Без интернета они дождутся связи."
            else
                "⚠️ Нет доступа к видео. Выдайте разрешение, иначе приложение не увидит новые записи."
            setPadding(0, 0, 0, dp(16))
        })

        if (!hasPerm) {
            root.addView(button("Выдать разрешение") { requestNeededPermissions() })
        }

        root.addView(button("Проверить и отправить сейчас") {
            SyncScheduler.runNow(this)
            Toast.makeText(this, "Запущено. Отправка идёт в фоне.", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("Выйти") {
            prefs.clearSession()
            SyncScheduler.disable(this)
            render()
        })
    }

    // ----------------------------------------------------------------------
    // Разрешения
    private fun hasVideoPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    // ----------------------------------------------------------------------
    // Мелкие помощники для построения UI
    private fun title(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        })
    }

    private fun input(hint: String, value: String = "", isPassword: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            if (isPassword) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
