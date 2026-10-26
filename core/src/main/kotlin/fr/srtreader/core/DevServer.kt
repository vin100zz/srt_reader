package fr.srtreader.core

import java.io.File

/**
 * Lance la page de contrôle sur le PC, sans TV, pour la tester ou la modifier :
 *     gradlew :core:run [--args="chemin\vers\film.srt"]
 * Le texte qui serait affiché sur la TV s'écrit dans la console.
 */
fun main(args: Array<String>) {
    val host = object : Host {
        override fun sendMediaKey(play: Boolean) = println(if (play) "[touche media] lecture" else "[touche media] pause")
    }
    val controller = Controller(dataDir = null, host = host, now = { System.nanoTime() / 1_000_000 })
    args.firstOrNull()?.let { File(it) }?.takeIf { it.isFile }?.let { controller.load(it.name, it.readText()) }

    // Relit la page depuis les sources : il suffit de recharger le navigateur après une modification
    val webRoot = File("src/main/resources/web").takeIf { it.isDirectory }
    // Clé OpenSubtitles gardée dans core/build/dev-data ; SRT_OPENSUBTITLES_URL pour viser un faux serveur
    val api = OpenSubtitles(System.getenv("SRT_OPENSUBTITLES_URL") ?: OpenSubtitles.DEFAULT_URL)
    val subtitles = SubtitleSearch(File("build/dev-data").apply { mkdirs() }, api)
    val port = ControlServer(controller, webRoot, subtitles).start(8080)
    println("Page de controle : http://localhost:$port")

    var shown: String? = null
    while (true) {
        val text = controller.overlayText()
        if (text != shown) {
            println(if (text == null) "   (rien a l'ecran)" else ">> " + text.replace("\n", " / "))
            shown = text
        }
        Thread.sleep(50)
    }
}
