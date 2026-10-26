package fr.srtreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import fr.srtreader.core.ControlServer
import fr.srtreader.core.Controller
import fr.srtreader.core.DisplaySettings
import fr.srtreader.core.Host
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Service au premier plan : affiche les sous-titres dans une fenêtre par-dessus l'appli
 * vidéo et fait tourner le serveur de la page de contrôle.
 *
 * La fenêtre ne prend jamais le focus : la télécommande continue de piloter Netflix & co.
 */
class OverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: Controller
    private lateinit var windowManager: WindowManager
    private lateinit var text: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var overlay: FrameLayout? = null

    @Volatile
    private var server: ControlServer? = null
    private var tracker: MediaTracker? = null
    private var shownText: String? = null
    private var appliedSettings = -1

    private val ticker = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        // Même horloge que les positions publiées par les sessions média
        controller = Controller(filesDir, TvHost(this)) { SystemClock.elapsedRealtime() }
        windowManager = getSystemService(WindowManager::class.java)
        try {
            addOverlay()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Impossible d'afficher la fenêtre de sous-titres", e)
            stopSelf()
            return
        }
        running = true
        thread(name = "srt-http-start") {
            try {
                val s = ControlServer(controller)
                val p = s.start(PORT)
                if (running) {
                    server = s
                    port = p
                } else {
                    s.stop()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Impossible de démarrer le serveur web", e)
            }
        }
        tracker = MediaTracker(this) { controller.updateExternal(it) }.also { it.start() }
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        port = 0
        handler.removeCallbacks(ticker)
        tracker?.stop()
        tracker = null
        server?.stop()
        server = null
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun addOverlay() {
        val padH = dp(18)
        val padV = dp(6)
        text = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setLineSpacing(0f, 1.1f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
            setPadding(padH, padV, padH, padV)
            visibility = View.INVISIBLE
        }
        val container = FrameLayout(this).apply {
            addView(text, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
        }
        params = WindowManager.LayoutParams(
            MATCH_PARENT,
            WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        windowManager.addView(container, params)
        overlay = container
    }

    private fun update() {
        val version = controller.settingsVersion
        if (version != appliedSettings) {
            appliedSettings = version
            applySettings(controller.settings)
        }
        val current = controller.overlayText()
        if (current != shownText) {
            shownText = current
            if (current == null) {
                text.visibility = View.INVISIBLE
            } else {
                text.text = Html.fromHtml(current.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY)
                text.visibility = View.VISIBLE
            }
        }
    }

    private fun applySettings(s: DisplaySettings) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSize.toFloat())
        text.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.argb(s.bgOpacity * 255 / 100, 0, 0, 0))
        }
        params.y = resources.displayMetrics.heightPixels * s.bottomPercent / 100
        overlay?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        var running = false
            private set

        @Volatile
        var port = 0
            private set

        private const val PORT = 8080
        private const val TICK_MS = 40L
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SrtReader"
    }
}

private class TvHost(private val context: Context) : Host {
    private val audio = context.getSystemService(AudioManager::class.java)

    /** Transmet Lecture/Pause à l'appli qui a la session média active (Netflix, Canal+...). */
    override fun sendMediaKey(play: Boolean) {
        val code = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
        val time = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
    }

    override fun mediaAccessGranted() = MediaTracker.accessGranted(context)
}
