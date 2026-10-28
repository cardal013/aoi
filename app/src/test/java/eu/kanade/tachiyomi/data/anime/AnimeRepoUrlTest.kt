package eu.kanade.tachiyomi.data.anime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnimeRepoUrlTest {

    private val raw = "https://raw.githubusercontent.com/dono/extensoes/repo"

    @Test
    fun `aceita o link de qualquer maneira`() {
        AnimeRepoUrl.base("$raw/index.min.json") shouldBe raw
        AnimeRepoUrl.base("  $raw/ ") shouldBe raw
        AnimeRepoUrl.base("raw.githubusercontent.com/dono/extensoes/repo/index.min.json") shouldBe raw
        AnimeRepoUrl.base("http://raw.githubusercontent.com/dono/extensoes/repo") shouldBe raw
        AnimeRepoUrl.base("https://github.com/dono/extensoes") shouldBe raw
        AnimeRepoUrl.base("github.com/dono/extensoes.git") shouldBe raw
        AnimeRepoUrl.base("https://github.com/dono/extensoes/blob/repo/index.min.json") shouldBe raw
        AnimeRepoUrl.base("aniyomi://add-repo?url=https%3A%2F%2Fraw.githubusercontent.com%2Fdono%2Fextensoes%2Frepo%2Findex.min.json") shouldBe raw
    }

    @Test
    fun `recusa o que nao e link`() {
        AnimeRepoUrl.base("") shouldBe null
        AnimeRepoUrl.base("ola") shouldBe null
        AnimeRepoUrl.base("aniyomi://add-repo") shouldBe null
    }
}
