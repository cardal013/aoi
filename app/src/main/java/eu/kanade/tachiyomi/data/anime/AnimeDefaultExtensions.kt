package eu.kanade.tachiyomi.data.anime

/**
 * Extensões de anime que o Aoi propõe instalar quando o aparelho ainda não tem nenhuma.
 * Cada entrada de [WANTED] pode ser o pacote (`eu.kanade.tachiyomi.animeextension.en.exemplo`) ou o nome
 * que aparece na lista dos repositórios; só se propõem as que estão nos repositórios.
 */
object AnimeDefaultExtensions {

    // Por preencher: o Afonso diz quais. Com a lista vazia a pergunta nunca aparece
    val WANTED: List<String> = emptyList()

    /** As de [wanted] que estão em [available] e ainda não estão no aparelho, pela ordem de [wanted]. */
    fun missing(
        available: List<AnimeExtensions.Available>,
        presentPkgs: Set<String>,
        wanted: List<String> = WANTED,
    ): List<AnimeExtensions.Available> = wanted
        .map { it.trim() }
        .mapNotNull { w -> available.find { it.pkg.equals(w, ignoreCase = true) || it.name.equals(w, ignoreCase = true) } }
        .filter { it.pkg !in presentPkgs }
        .distinctBy { it.pkg }
}
