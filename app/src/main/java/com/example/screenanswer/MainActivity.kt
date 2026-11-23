package com.example.screenanswer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var keyField: EditText

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", data)
                startForegroundService(i)
                Toast.makeText(this, "Готово! Нажимай на кнопку «?» поверх экрана", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val pad = (16 * resources.displayMetrics.density).toInt()
        keyField = EditText(this).apply {
            hint = "Anthropic API key (sk-ant-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("prefs", MODE_PRIVATE).getString("key", ""))
        }
        val button = Button(this).apply {
            text = "Запустить"
            setOnClickListener { start() }
        }
        val info = TextView(this).apply {
            text = "Появится кнопка «?». Нажми её на любом экране — приложение снимет экран, " +
                "найдёт ответ в интернете и покажет его."
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            addView(info); addView(keyField); addView(button)
        })
    }

    private fun start() {
        val key = keyField.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Введи API-ключ", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("key", key).apply()

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Разреши показ поверх других окон и нажми «Запустить» ещё раз", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
