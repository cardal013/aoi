package eu.kanade.tachiyomi.data.anime

import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnimeMatchTest {

    private val oshi = listOf("[Oshi no Ko]", "[Oshi No Ko]")
    private val oshi2 = listOf("[Oshi no Ko] 2nd Season", "[Oshi No Ko] Season 2")

    @Test
    fun `temporadas escritas de outra maneira contam como iguais`() {
        AnimeMatch.normalize("[Oshi no Ko] 2nd Season") shouldBe AnimeMatch.normalize("Oshi no Ko Season 2")
        AnimeMatch.score(oshi2, "Oshi no Ko Season 2") shouldBe 1.0
        AnimeMatch.score(oshi, "Oshi no Ko") shouldBe 1.0
    }

    @Test
    fun `outra temporada ou dobrado nao se escolhem sozinhos`() {
        AnimeMatch.score(oshi, "Oshi no Ko Season 2") shouldBeLessThan AnimeMatch.AUTO_PICK
        AnimeMatch.score(oshi2, "Oshi no Ko") shouldBeLessThan AnimeMatch.AUTO_PICK
        AnimeMatch.score(oshi, "Oshi no Ko (Dub)") shouldBeLessThan AnimeMatch.score(oshi, "Oshi no Ko")
    }

    @Test
    fun `pequenas diferencas de escrita passam`() {
        AnimeMatch.score(listOf("Shingeki no Kyojin", "Attack on Titan"), "Attack on Titan") shouldBe 1.0
        AnimeMatch.score(listOf("Kimetsu no Yaiba"), "Kimetsu no Yaiba:") shouldBe 1.0
        AnimeMatch.score(listOf("Re:Zero kara Hajimeru Isekai Seikatsu"), "Re:Zero - Kara Hajimeru Isekai Seikatsu") shouldBeGreaterThanOrEqual
            AnimeMatch.AUTO_PICK
        AnimeMatch.score(listOf("Naruto"), "One Piece") shouldBeLessThan 0.3
    }
}
