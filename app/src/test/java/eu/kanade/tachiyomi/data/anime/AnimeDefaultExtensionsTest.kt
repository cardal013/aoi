package eu.kanade.tachiyomi.data.anime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnimeDefaultExtensionsTest {

    private fun available(pkg: String, name: String) = AnimeExtensions.Available(
        pkg = pkg,
        name = name,
        versionName = "14.1",
        versionCode = 1,
        lang = "en",
        nsfw = false,
        apkUrl = "",
        iconUrl = "",
    )

    private val a = available("eu.kanade.tachiyomi.animeextension.en.a", "Alpha")
    private val b = available("eu.kanade.tachiyomi.animeextension.en.b", "Beta")

    @Test
    fun `aceita o pacote ou o nome e segue a ordem pedida`() {
        AnimeDefaultExtensions.missing(listOf(a, b), emptySet(), listOf("beta", " ${a.pkg} ")) shouldBe listOf(b, a)
    }

    @Test
    fun `deixa de fora as que já estão no aparelho e as que não estão nos repositórios`() {
        AnimeDefaultExtensions.missing(listOf(a, b), setOf(a.pkg), listOf("Alpha", "Beta", "Gamma")) shouldBe listOf(b)
        AnimeDefaultExtensions.missing(listOf(a), emptySet(), emptyList()) shouldBe emptyList()
    }
}
