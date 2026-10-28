package eu.kanade.tachiyomi.data.anime

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/**
 * Link de um repositório de extensões de anime, escrito de qualquer maneira: o index.min.json, a pasta
 * onde ele está, o repositório no GitHub, um `aniyomi://add-repo?url=…` ou sem `https://`.
 */
object AnimeRepoUrl {

    private val GITHUB_FILE = Regex("""^https://github\.com/([^/]+)/([^/]+)/(?:blob|tree|raw)/([^/]+)(/.*)?$""")
    private val GITHUB_REPO = Regex("""^https://github\.com/([^/]+)/([^/]+?)(?:\.git)?/?$""")
    private val URL_PARAM = Regex("""[?&]url=([^&#]+)""")

    /** Pasta do repositório (sem o index.min.json no fim), ou null se não parecer um link. */
    fun base(input: String): String? {
        var url = input.trim()
        if (url.isEmpty()) return null
        if (!url.startsWith("http", ignoreCase = true) && "://" in url) {
            val param = URL_PARAM.find(url)?.groupValues?.get(1) ?: return null
            url = URLDecoder.decode(param, "UTF-8").trim()
        }
        if ("://" !in url) url = "https://$url"
        url = url.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        GITHUB_FILE.find(url)?.destructured?.let { (owner, repo, branch, path) ->
            url = "https://raw.githubusercontent.com/$owner/$repo/$branch$path"
        }
        // Os repositórios de extensões no GitHub publicam o index no ramo "repo"
        GITHUB_REPO.find(url)?.destructured?.let { (owner, repo) ->
            url = "https://raw.githubusercontent.com/$owner/$repo/repo"
        }
        val base = url.trimEnd('/')
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/repo.json")
            .trimEnd('/')
        return base.takeIf { it.toHttpUrlOrNull()?.host?.contains('.') == true }
    }
}
