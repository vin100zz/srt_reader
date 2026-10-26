package fr.srtreader.core

import java.io.File
import java.util.Properties

/**
 * Recherche et téléchargement de sous-titres sur OpenSubtitles, avec la clé d'API (et le
 * compte, facultatif) saisis sur le téléphone. Ils sont gardés dans [dataDir], sur la TV.
 *
 * Indépendant du [Controller] : les appels réseau durent plusieurs secondes et ne doivent pas
 * bloquer l'affichage des sous-titres.
 */
class SubtitleSearch(private val dataDir: File?, private val api: OpenSubtitles = OpenSubtitles()) {
    @Volatile
    private var credentials = restore()

    fun statusJson(): String = JsonObj()
        .put("configured", credentials.apiKey.isNotBlank())
        .put("username", credentials.username.ifBlank { null })
        .toString()

    /** Une clé ou un mot de passe vide garde la valeur déjà enregistrée. */
    @Synchronized
    fun configure(apiKey: String, username: String, password: String) {
        val current = credentials
        val user = username.trim()
        credentials = OpenSubtitles.Credentials(
            apiKey = apiKey.trim().ifEmpty { current.apiKey },
            username = user,
            password = when {
                user.isEmpty() -> ""
                password.isNotEmpty() -> password
                user == current.username -> current.password
                else -> ""
            },
        )
        save()
    }

    fun searchJson(query: String, season: Int?, episode: Int?, preferNetflix: Boolean): String {
        require(query.isNotBlank()) { "Tape le titre d'un film ou d'une série" }
        val results = api.search(requireKey(), query, season, episode)
        return toJson(OpenSubtitles.rank(results, preferNetflix).take(MAX_RESULTS).map {
            JsonObj()
                .put("file", it.fileId)
                .put("title", it.title)
                .put("release", it.release)
                .put("downloads", it.downloads)
                .put("fps", it.fps)
                .put("hi", it.hearingImpaired)
                .put("auto", it.machineTranslated)
                .put("netflix", it.netflix)
        })
    }

    fun download(fileId: Long): OpenSubtitles.Download = api.download(requireKey(), fileId)

    private fun requireKey(): OpenSubtitles.Credentials = credentials.also {
        require(it.apiKey.isNotBlank()) { "Enregistre d'abord ta clé d'API OpenSubtitles (en bas de la page)" }
    }

    private fun save() {
        val dir = dataDir ?: return
        val p = Properties()
        with(credentials) {
            p.setProperty("apiKey", apiKey)
            p.setProperty("username", username)
            p.setProperty("password", password)
        }
        runCatching { File(dir, FILE).outputStream().use { p.store(it, null) } }
    }

    private fun restore(): OpenSubtitles.Credentials {
        val p = Properties()
        dataDir?.let { File(it, FILE) }?.takeIf { it.isFile }?.let { f -> runCatching { f.inputStream().use { p.load(it) } } }
        return OpenSubtitles.Credentials(
            apiKey = p.getProperty("apiKey").orEmpty(),
            username = p.getProperty("username").orEmpty(),
            password = p.getProperty("password").orEmpty(),
        )
    }

    private companion object {
        const val FILE = "opensubtitles.properties"
        const val MAX_RESULTS = 30
    }
}
