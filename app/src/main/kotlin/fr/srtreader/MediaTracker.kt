package fr.srtreader

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import fr.srtreader.core.ExternalPlayback

/**
 * Vide : sa seule présence, une fois l'accès aux notifications accordé, permet de lire
 * les sessions média des autres applis.
 */
class MediaListener : NotificationListenerService()

/**
 * Suit la session média de l'appli vidéo (Netflix...) et transmet sa position de lecture
 * à chaque changement : lecture, pause, saut, et mise à jour périodique.
 */
class MediaTracker(private val context: Context, private val onChange: (ExternalPlayback?) -> Unit) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refresh()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }
    private val retry = Runnable { start() }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            started = true
            attach(manager.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // Accès aux notifications pas (encore) accordé : on réessaie un peu plus tard
            handler.postDelayed(retry, RETRY_MS)
        }
    }

    fun stop() {
        handler.removeCallbacks(retry)
        if (started) manager.removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        started = false
    }

    private fun refresh() {
        if (started) runCatching { attach(manager.getActiveSessions(listener)) }
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list.filter { it.packageName != context.packageName }
        controllers.forEach { it.registerCallback(callback, handler) }
        publish()
    }

    /** Retient l'appli en lecture, sinon la plus récente qui donne une position (en pause...). */
    private fun publish() {
        val candidates = controllers.mapNotNull { c -> c.playbackState?.takeIf(::hasPosition)?.let { c to it } }
        val (controller, state) = candidates.firstOrNull { it.second.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull()
            ?: return onChange(null)
        onChange(
            ExternalPlayback(
                source = appName(controller.packageName),
                playing = state.state == PlaybackState.STATE_PLAYING,
                positionMs = state.position,
                updatedAt = state.lastPositionUpdateTime,
                speed = state.playbackSpeed,
                title = controller.metadata?.let {
                    it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                },
            )
        )
    }

    private fun hasPosition(state: PlaybackState) = state.position >= 0 && state.state in USABLE_STATES

    private fun appName(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        private const val RETRY_MS = 3000L
        private val USABLE_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
        )

        fun accessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return enabled?.contains(ComponentName(context, MediaListener::class.java).flattenToString()) == true
        }
    }
}
