package fr.srtreader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/** Écran d'accueil sur la TV : autorisation, démarrage du service, adresse à ouvrir sur le téléphone. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var url: TextView
    private lateinit var help: TextView
    private lateinit var qr: ImageView
    private lateinit var permission: Button
    private lateinit var toggle: Button
    private var shownUrl: String? = null
    private var stoppedByUser = false

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        url = findViewById(R.id.url)
        help = findViewById(R.id.help)
        qr = findViewById(R.id.qr)
        permission = findViewById(R.id.permission)
        toggle = findViewById(R.id.toggle)

        permission.setOnClickListener { openOverlaySettings() }
        toggle.setOnClickListener {
            if (OverlayService.running) {
                stoppedByUser = true
                stopService(Intent(this, OverlayService::class.java))
            } else {
                stoppedByUser = false
                startOverlay()
            }
            handler.postDelayed({ refresh() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this) && !OverlayService.running && !stoppedByUser) startOverlay()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun startOverlay() {
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            // Fréquent sur Google TV : le réglage n'existe pas dans l'interface
            help.text = getString(R.string.help_permission_adb, packageName)
        }
    }

    private fun refresh() {
        val allowed = Settings.canDrawOverlays(this)
        val running = OverlayService.running
        val port = OverlayService.port
        val ip = Net.localIpv4()
        val address = if (running && port > 0 && ip != null) "http://$ip:$port" else null

        permission.visibility = if (allowed) View.GONE else View.VISIBLE
        toggle.isEnabled = allowed
        toggle.setText(if (running) R.string.stop else R.string.start)
        status.setText(
            when {
                !allowed -> R.string.status_permission
                address != null -> R.string.status_ready
                running && ip == null -> R.string.status_no_network
                running -> R.string.status_starting
                else -> R.string.status_stopped
            }
        )
        if (allowed) help.setText(R.string.help_usage) else help.text = getString(R.string.help_permission_adb, packageName)

        if (address != shownUrl) {
            shownUrl = address
            url.text = address.orEmpty()
            qr.setImageBitmap(address?.let { Qr.bitmap(it, 512) })
            qr.visibility = if (address == null) View.INVISIBLE else View.VISIBLE
        }
        if (currentFocus == null) (if (allowed) toggle else permission).requestFocus()
    }
}
