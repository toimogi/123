package com.example.screenanswer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("capture").apply { start() }
    private val bg by lazy { Handler(bgThread.looper) }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var button: TextView? = null
    private var panel: View? = null

    private val lock = Any()
    private var latest: Image? = null
    private var w = 0
    private var h = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java) else intent?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        release()
        startProjection(intent!!.getIntExtra("code", 0), data)
        showButton()
        return START_NOT_STICKY
    }

    // ---------- screen capture ----------

    private fun startProjection(code: Int, data: Intent) {
        val m = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        m.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { stopSelf() } }
        }, ui)

        val b = wm.currentWindowMetrics.bounds
        w = b.width(); h = b.height()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            try {
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(lock) { latest?.close(); latest = img }
            } catch (_: Exception) {}
        }, bg)
        display = m.createVirtualDisplay(
            "cap", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        projection = m; reader = r
    }

    /** Returns the latest frame as JPEG (downscaled), or null. */
    private fun grab(): ByteArray? = synchronized(lock) {
        val img = latest ?: return null
        val p = img.planes[0]
        p.buffer.rewind()
        val rowPad = p.rowStride - p.pixelStride * w
        val full = Bitmap.createBitmap(w + rowPad / p.pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        val crop = Bitmap.createBitmap(full, 0, 0, w, h)
        val k = minOf(1f, 1400f / maxOf(w, h))
        val out = if (k < 1f) Bitmap.createScaledBitmap(crop, (w * k).toInt(), (h * k).toInt(), true) else crop
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    // ---------- Claude API: reads the screenshot + searches the web ----------

    private fun ask(jpeg: ByteArray): String {
        val key = getSharedPreferences("prefs", MODE_PRIVATE).getString("key", "") ?: ""
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = JSONObject()
            .put("model", "claude-sonnet-5-5")
            .put("max_tokens", 1024)
            .put("system", "На скриншоте вопрос или задание. Определи его, при необходимости найди " +
                "актуальную информацию через веб-поиск и дай короткий точный ответ на языке вопроса.")
            .put("tools", JSONArray().put(JSONObject()
                .put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3)))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("type", "text").put("text", "Ответь на вопрос на скриншоте.")))))

        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 90000
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream))
            .bufferedReader().use { it.readText() }
        if (code !in 200..299) return "Ошибка API $code: $txt".take(400)

        val arr = JSONObject(txt).getJSONArray("content")
        return buildString {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") == "text") append(o.getString("text"))
            }
        }.ifBlank { "Пустой ответ" }
    }

    // ---------- UI ----------

    private fun onTap() {
        hidePanel()
        button?.visibility = View.INVISIBLE          // чтобы кнопки не было на скриншоте
        ui.postDelayed({
            val jpeg = grab()
            button?.visibility = View.VISIBLE
            if (jpeg == null) { showPanel("Не удалось снять экран, попробуй ещё раз"); return@postDelayed }
            showPanel("Ищу ответ…")
            Thread {
                val answer = try { ask(jpeg) } catch (e: Exception) { "Ошибка: ${e.message}" }
                ui.post { showPanel(answer) }
            }.start()
        }, 350)
    }

    private fun showButton() {
        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()
        val tv = TextView(this).apply {
            text = "?"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC3F51B5.toInt()) }
        }
        val lp = LP(size, size, LP.TYPE_APPLICATION_OVERLAY, LP.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 400 }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) { lp.x = ox + dx; lp.y = oy + dy; wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        wm.addView(tv, lp); button = tv
    }

    private fun showPanel(text: String) {
        hidePanel()
        val d = resources.displayMetrics.density
        val p = (12 * d).toInt()
        val close = TextView(this).apply {
            this.text = "✕ закрыть"; setTextColor(0xFFBBBBBB.toInt()); gravity = Gravity.END
            setPadding(p, p, p, 0); setOnClickListener { hidePanel() }
        }
        val answer = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 16f
            setPadding(p, p / 2, p, p); maxHeight = (h * 0.4).toInt()
            movementMethod = ScrollingMovementMethod()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(0xEE212121.toInt()) }
            addView(close); addView(answer)
        }
        val lp = LP(w - (32 * d).toInt(), LP.WRAP_CONTENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (48 * d).toInt() }
        wm.addView(box, lp); panel = box
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("main", "Screen Answer", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        return Notification.Builder(this, "main")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Screen Answer работает")
            .addAction(0, "Стоп", stop)
            .build()
    }

    private fun release() {
        hidePanel()
        button?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        button = null
        display?.release(); display = null
        synchronized(lock) { latest?.close(); latest = null }
        reader?.close(); reader = null
        projection?.stop(); projection = null
    }

    override fun onDestroy() {
        release()
        bgThread.quitSafely()
        super.onDestroy()
    }
}
