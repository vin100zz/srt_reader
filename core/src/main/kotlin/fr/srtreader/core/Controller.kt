package fr.srtreader.core

import java.io.File
import java.util.Locale
import java.util.Properties

data class DisplaySettings(
    val fontSize: Int = 28,      // sp
    val bottomPercent: Int = 8,  // distance au bas de l'écran, en % de la hauteur
    val bgOpacity: Int = 55,     // opacité du fond derrière le texte, en %
    val visible: Boolean = true,
    val mediaKeys: Boolean = true, // Lecture/Pause pilote aussi l'appli vidéo
    val follow: Boolean = true,    // l'horloge suit la position publiée par l'appli vidéo
)

/** Ce que la plateforme (TV ou PC de test) fournit au contrôleur. */
interface Host {
    /** Envoie Lecture ou Pause à l'appli vidéo en cours (Netflix, Canal+...). */
    fun sendMediaKey(play: Boolean)

    /** L'appli a-t-elle le droit de lire l'état de lecture des autres applis ? */
    fun mediaAccessGranted(): Boolean = false
}

/**
 * Point d'entrée partagé entre le serveur web (plusieurs threads) et la fenêtre de
 * sous-titres : toutes les méthodes publiques sont synchronisées.
 * L'état est sauvegardé dans [dataDir] (si fourni) pour survivre à un redémarrage.
 */
class Controller(private val dataDir: File?, private val host: Host, now: () -> Long) {
    private val engine = SyncEngine(now)
    private var fileName: String? = null
    private var message = ""
    private var messageId = 0

    /** Dernier état publié par l'appli vidéo, même quand le suivi est coupé. */
    private var latestExternal: ExternalPlayback? = null

    @Volatile
    var settings = DisplaySettings()
        private set

    @Volatile
    var settingsVersion = 0
        private set

    init {
        restore()
    }

    @Synchronized
    fun load(name: String, content: String) {
        val cues = SrtParser.parse(content)
        require(cues.isNotEmpty()) { "Aucune réplique trouvée : est-ce bien un fichier .srt ?" }
        engine.load(cues)
        fileName = name
        dataDir?.let { File(it, SRT_FILE).writeText(content) }
        say("${cues.size} répliques chargées")
        save()
    }

    @Synchronized
    fun handle(action: String, params: Map<String, String>) {
        fun long(key: String) =
            params[key]?.toLongOrNull() ?: throw IllegalArgumentException("Paramètre « $key » manquant")

        when (action) {
            "play" -> play()
            "pause" -> pause()
            "toggle" -> if (engine.playing) pause() else play()
            "seek" -> engine.seekBy(long("ms"))
            "seekto" -> engine.seekTo(long("ms"))
            "nudge" -> engine.nudge(long("ms"))
            "sync" -> sync(engine.cues.getOrNull(long("i").toInt()) ?: throw IllegalArgumentException("Réplique inconnue"))
            "syncnext" -> sync(
                engine.nextAfter(engine.position()) ?: throw IllegalArgumentException("Plus aucune réplique après ce point")
            )
            "scale" -> {
                val v = params["v"]?.toDoubleOrNull()?.takeIf { it in 0.5..2.0 }
                    ?: throw IllegalArgumentException("Vitesse invalide")
                engine.setScale(v)
                say("Vitesse : ${Ratios.describe(v)}")
            }
            "resetsync" -> {
                engine.resetSync()
                say("Recalage remis à zéro")
            }
            "set" -> setSetting(params["k"], params["v"])
            else -> throw IllegalArgumentException("Action inconnue : $action")
        }
        save()
    }

    /** Nouvel état de l'appli vidéo (null : plus aucune appli ne publie sa position). */
    @Synchronized
    fun updateExternal(state: ExternalPlayback?) {
        latestExternal = state
        applyFollow()
    }

    private fun applyFollow() {
        val before = engine.external?.source
        engine.follow(if (settings.follow) latestExternal else null)
        val after = engine.external?.source
        when {
            after != null && after != before -> say("Synchro automatique avec $after")
            after == null && before != null -> say("$before ne donne plus sa position : synchro manuelle")
        }
    }

    /** Texte à afficher sur la TV en ce moment, ou null. */
    @Synchronized
    fun overlayText(): String? {
        if (!settings.visible) return null
        val active = engine.activeAt(engine.position())
        return if (active.isEmpty()) null else active.joinToString("\n") { it.text }
    }

    @Synchronized
    fun stateJson(): String {
        val t = engine.position()
        val cues = engine.cues
        val active = engine.activeAt(t)
        val next = engine.nextAfter(t)
        val anchor = active.firstOrNull()?.index ?: next?.index ?: cues.size
        val nearby = cues.subList((anchor - 3).coerceAtLeast(0), (anchor + 5).coerceAtMost(cues.size))
        val s = settings
        return JsonObj()
            .put("loaded", cues.isNotEmpty())
            .put("name", fileName)
            .put("count", cues.size)
            .put("playing", engine.playing)
            .put("following", engine.external?.source)
            .put("mediaAccess", host.mediaAccessGranted())
            .put("position", t)
            .put("offset", engine.offsetMs)
            .put("scale", engine.scale)
            .put("scaleLabel", Ratios.describe(engine.scale))
            .put("synced", engine.lastSync != null)
            .put("current", active.map { it.index })
            .put("currentText", active.joinToString("\n") { it.text })
            .put("next", next?.let { cueJson(it) })
            .put("nearby", nearby.map { cueJson(it) })
            .put("ratios", Ratios.known.map { JsonObj().put("v", it.first).put("label", it.second) })
            .put(
                "settings", JsonObj()
                    .put("fontSize", s.fontSize)
                    .put("bottom", s.bottomPercent)
                    .put("bg", s.bgOpacity)
                    .put("visible", s.visible)
                    .put("mediaKeys", s.mediaKeys)
                    .put("follow", s.follow)
            )
            .put("message", message)
            .put("messageId", messageId)
            .toString()
    }

    @Synchronized
    fun searchJson(query: String): String {
        val needle = normalizeForSearch(query)
        val hits = if (needle.length < 2) emptyList()
        else engine.cues.filter { it.searchText.contains(needle) }.take(MAX_RESULTS)
        return toJson(hits.map { cueJson(it) })
    }

    private fun cueJson(cue: Cue) = JsonObj()
        .put("i", cue.index)
        .put("t", engine.filmStart(cue))
        .put("text", cue.text)

    // Quand on suit l'appli vidéo, Lecture/Pause n'a de sens que si on la pilote
    private fun play() {
        engine.play()
        if (settings.mediaKeys || engine.external != null) host.sendMediaKey(true)
    }

    private fun pause() {
        engine.pause()
        if (settings.mediaKeys || engine.external != null) host.sendMediaKey(false)
    }

    private fun sync(cue: Cue) {
        val outcome = engine.syncTo(cue)
        val measured = outcome.measured
        say(
            when {
                measured == null -> "Recalé sur cette réplique"
                outcome.applied != null -> "Recalé. Vitesse mesurée sur 2 points : ${Ratios.describe(outcome.applied)}"
                else -> "Recalé. Vitesse mesurée incohérente (×${"%.4f".format(Locale.FRANCE, measured)}), " +
                    "sans doute un saut dans le film : vitesse inchangée"
            }
        )
    }

    private fun setSetting(key: String?, value: String?) {
        fun int() = value?.toIntOrNull() ?: throw IllegalArgumentException("Valeur invalide")
        fun bool() = value == "1" || value == "true"
        val s = settings
        settings = when (key) {
            "fontSize" -> s.copy(fontSize = int().coerceIn(12, 72))
            "bottom" -> s.copy(bottomPercent = int().coerceIn(0, 85))
            "bg" -> s.copy(bgOpacity = int().coerceIn(0, 100))
            "visible" -> s.copy(visible = bool())
            "mediaKeys" -> s.copy(mediaKeys = bool())
            "follow" -> s.copy(follow = bool())
            else -> throw IllegalArgumentException("Réglage inconnu : $key")
        }
        settingsVersion++
        if (key == "follow") applyFollow()
    }

    private fun say(text: String) {
        message = text
        messageId++
    }

    private fun save() {
        val dir = dataDir ?: return
        val p = Properties()
        fileName?.let { p.setProperty("name", it) }
        p.setProperty("offset", engine.offsetMs.toString())
        p.setProperty("scale", engine.scale.toString())
        p.setProperty("position", engine.position().toString())
        engine.lastSync?.let {
            p.setProperty("syncSrt", it.srtMs.toString())
            p.setProperty("syncFilm", it.filmMs.toString())
        }
        with(settings) {
            p.setProperty("fontSize", fontSize.toString())
            p.setProperty("bottom", bottomPercent.toString())
            p.setProperty("bg", bgOpacity.toString())
            p.setProperty("visible", visible.toString())
            p.setProperty("mediaKeys", mediaKeys.toString())
            p.setProperty("follow", follow.toString())
        }
        runCatching { File(dir, STATE_FILE).outputStream().use { p.store(it, null) } }
    }

    private fun restore() {
        val dir = dataDir ?: return
        runCatching {
            val p = Properties()
            File(dir, STATE_FILE).takeIf { it.isFile }?.inputStream()?.use { p.load(it) }
            val srt = File(dir, SRT_FILE)
            if (srt.isFile) {
                val cues = SrtParser.parse(srt.readText())
                if (cues.isNotEmpty()) {
                    engine.load(cues)
                    fileName = p.getProperty("name")
                }
            }
            val syncSrt = p.getProperty("syncSrt")?.toLongOrNull()
            val syncFilm = p.getProperty("syncFilm")?.toLongOrNull()
            engine.restore(
                offsetMs = p.getProperty("offset")?.toLongOrNull() ?: 0,
                scale = p.getProperty("scale")?.toDoubleOrNull() ?: 1.0,
                positionMs = p.getProperty("position")?.toLongOrNull() ?: 0,
                lastSync = if (syncSrt != null && syncFilm != null) SyncEngine.SyncPoint(syncSrt, syncFilm) else null,
            )
            val d = DisplaySettings()
            settings = DisplaySettings(
                fontSize = p.getProperty("fontSize")?.toIntOrNull() ?: d.fontSize,
                bottomPercent = p.getProperty("bottom")?.toIntOrNull() ?: d.bottomPercent,
                bgOpacity = p.getProperty("bg")?.toIntOrNull() ?: d.bgOpacity,
                visible = p.getProperty("visible")?.toBooleanStrictOrNull() ?: d.visible,
                mediaKeys = p.getProperty("mediaKeys")?.toBooleanStrictOrNull() ?: d.mediaKeys,
                follow = p.getProperty("follow")?.toBooleanStrictOrNull() ?: d.follow,
            )
        }
    }

    private companion object {
        const val SRT_FILE = "current.srt"
        const val STATE_FILE = "state.properties"
        const val MAX_RESULTS = 40
    }
}
