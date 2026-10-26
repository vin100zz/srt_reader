package fr.srtreader.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * État de lecture publié par l'appli vidéo (Netflix...) dans sa session média.
 * [updatedAt] est sur la même horloge que celle du [SyncEngine].
 */
data class ExternalPlayback(
    val source: String,
    val playing: Boolean,
    val positionMs: Long,
    val updatedAt: Long,
    val speed: Float = 1f,
    val title: String? = null,
) {
    fun positionAt(now: Long): Long =
        (if (playing) positionMs + ((now - updatedAt) * speed).toLong() else positionMs).coerceAtLeast(0)
}

/**
 * Horloge du film et correspondance entre le temps du SRT et celui du film :
 *
 *     tFilm = tSrt * scale + offsetMs
 *
 * L'horloge suit l'appli vidéo quand elle publie sa position ([follow]), sinon elle avance
 * en temps réel entre Lecture et Pause. [scale] corrige les écarts de cadence (SRT fait pour
 * une version 25 i/s alors que le film tourne à 23,976 i/s...).
 */
class SyncEngine(private val now: () -> Long) {
    var cues: List<Cue> = emptyList()
        private set

    /** Appli vidéo suivie, prioritaire sur l'horloge manuelle. */
    var external: ExternalPlayback? = null
        private set

    val playing: Boolean
        get() = external?.playing ?: manualPlaying

    private var manualPlaying = false

    var offsetMs = 0L
        private set
    var scale = 1.0
        private set

    /** Dernier recalage : sert à mesurer la vitesse au recalage suivant. */
    var lastSync: SyncPoint? = null
        private set

    private var baseFilmMs = 0L
    private var baseClock = 0L

    data class SyncPoint(val srtMs: Long, val filmMs: Long)

    /** [measured] : vitesse mesurée entre deux recalages éloignés ; [applied] : vitesse retenue. */
    data class SyncOutcome(val measured: Double?, val applied: Double?)

    fun load(newCues: List<Cue>) {
        cues = newCues
        resetSync()
    }

    fun restore(offsetMs: Long, scale: Double, positionMs: Long, lastSync: SyncPoint?) {
        this.offsetMs = offsetMs
        this.scale = scale
        this.lastSync = lastSync
        seekTo(positionMs)
    }

    fun position(): Long = external?.positionAt(now()) ?: manualPosition()

    private fun manualPosition(): Long = if (manualPlaying) baseFilmMs + (now() - baseClock) else baseFilmMs

    fun play() {
        if (manualPlaying) return
        baseClock = now()
        manualPlaying = true
    }

    fun pause() {
        if (!manualPlaying) return
        baseFilmMs = manualPosition()
        manualPlaying = false
    }

    /**
     * Suit la position publiée par l'appli vidéo. Avec null (appli fermée, suivi coupé),
     * l'horloge manuelle reprend là où en était l'appli, en pause.
     */
    fun follow(state: ExternalPlayback?) {
        val previous = external
        if (state == null && previous != null) {
            baseFilmMs = previous.positionAt(now())
            baseClock = now()
            manualPlaying = false
        }
        external = state
    }

    /** Déplace l'horloge du film (pour suivre une avance ou un retour rapide dans l'appli vidéo). */
    fun seekTo(filmMs: Long) {
        baseFilmMs = filmMs.coerceAtLeast(0)
        baseClock = now()
    }

    fun seekBy(deltaMs: Long) = seekTo(position() + deltaMs)

    /** Décale les sous-titres sans toucher à l'horloge du film. */
    fun nudge(deltaMs: Long) {
        offsetMs += deltaMs
    }

    fun filmStart(cue: Cue): Long = (cue.startMs * scale).roundToLong() + offsetMs

    private fun srtTime(filmMs: Long): Double = (filmMs - offsetMs) / scale

    /** Répliques affichées à l'instant [filmMs] (plusieurs si elles se chevauchent). */
    fun activeAt(filmMs: Long): List<Cue> {
        val t = srtTime(filmMs)
        val last = lastStartingAtOrBefore(t)
        val active = ArrayList<Cue>(2)
        var i = last
        while (i >= 0 && last - i < 20) {
            if (cues[i].endMs > t) active.add(0, cues[i])
            i--
        }
        return active
    }

    /** Première réplique qui commence après [filmMs]. */
    fun nextAfter(filmMs: Long): Cue? = cues.getOrNull(lastStartingAtOrBefore(srtTime(filmMs)) + 1)

    private fun lastStartingAtOrBefore(srtMs: Double): Int {
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= srtMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /**
     * « Cette réplique est dite maintenant ». Si le recalage précédent est assez loin dans
     * le film, on en déduit aussi la vitesse, mais seulement si elle correspond à une
     * conversion de cadence connue : sinon c'est probablement qu'on a sauté dans le film.
     */
    fun syncTo(cue: Cue): SyncOutcome {
        val t = position()
        val previous = lastSync
        var measured: Double? = null
        var applied: Double? = null
        if (previous != null && abs(cue.startMs - previous.srtMs) >= MIN_SPAN_FOR_SPEED_MS) {
            measured = (t - previous.filmMs).toDouble() / (cue.startMs - previous.srtMs)
            applied = Ratios.snap(measured)
            if (applied != null) scale = applied
        }
        offsetMs = t - (cue.startMs * scale).roundToLong()
        lastSync = SyncPoint(cue.startMs, t)
        return SyncOutcome(measured, applied)
    }

    /** Change la vitesse sans faire sauter les sous-titres affichés en ce moment. */
    fun setScale(newScale: Double) {
        val t = position()
        val srt = srtTime(t)
        scale = newScale
        offsetMs = t - (srt * newScale).roundToLong()
    }

    fun resetSync() {
        offsetMs = 0
        scale = 1.0
        lastSync = null
    }

    companion object {
        const val MIN_SPAN_FOR_SPEED_MS = 15 * 60 * 1000L
    }
}

/** Conversions de cadence courantes entre la version du SRT et celle du film. */
object Ratios {
    private const val NTSC_FILM = 24000.0 / 1001.0 // 23,976 i/s

    val known: List<Pair<Double, String>> = listOf(
        1.0 to "Normale",
        25.0 / NTSC_FILM to "SRT 25 i/s → film 23,976 i/s",
        NTSC_FILM / 25.0 to "SRT 23,976 i/s → film 25 i/s",
        25.0 / 24.0 to "SRT 25 i/s → film 24 i/s",
        24.0 / 25.0 to "SRT 24 i/s → film 25 i/s",
        24.0 / NTSC_FILM to "SRT 24 i/s → film 23,976 i/s",
        NTSC_FILM / 24.0 to "SRT 23,976 i/s → film 24 i/s",
    )

    private const val TOLERANCE = 0.0015

    /** Ratio connu le plus proche de [measured], ou null si aucun n'est assez proche. */
    fun snap(measured: Double): Double? =
        known.map { it.first }
            .minByOrNull { abs(measured / it - 1) }
            ?.takeIf { abs(measured / it - 1) <= TOLERANCE }

    fun describe(scale: Double): String {
        val label = known.firstOrNull { abs(it.first - scale) < 1e-9 }?.second
        val factor = "×" + String.format(Locale.FRANCE, "%.4f", scale)
        return if (label == null) factor else "$label ($factor)"
    }
}
