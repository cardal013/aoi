package eu.kanade.tachiyomi.data.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryRequestsTest {

    private fun candidate(title: String, source: String = "1") =
        RequestCandidate(sourceId = source, sourceName = "Fonte $source", url = "/$source/$title", title = title)

    @Test
    fun `um só resultado entra sem perguntar`() {
        val only = candidate("Something else")
        pickRequestCandidate("solo leveling", listOf(only)) shouldBe only
    }

    @Test
    fun `entre vários entra o único com o título igual`() {
        val exact = candidate("Solo Leveling")
        val results = listOf(candidate("Solo Leveling: Ragnarok"), exact, candidate("Solo Max-Level Newbie"))
        pickRequestCandidate("solo leveling", results) shouldBe exact
        // Pontuação e maiúsculas não contam
        pickRequestCandidate("SOLO-LEVELING!", results) shouldBe exact
    }

    @Test
    fun `o mesmo título em duas fontes pergunta`() {
        val results = listOf(candidate("Solo Leveling", "1"), candidate("Solo Leveling", "2"))
        pickRequestCandidate("Solo Leveling", results) shouldBe null
    }

    @Test
    fun `sem título igual pergunta`() {
        pickRequestCandidate("solo", listOf(candidate("Solo Leveling"), candidate("Solo Max"))) shouldBe null
        pickRequestCandidate("solo", emptyList()) shouldBe null
    }

    @Test
    fun `as opções começam pelas de título igual`() {
        val exact = candidate("Solo Leveling", "2")
        val other = candidate("Solo Leveling: Ragnarok", "1")
        sortCandidates("Solo Leveling", listOf(other, exact)) shouldBe listOf(exact, other)
    }

    @Test
    fun `links do site da fonte`() {
        hostOf("https://www.mangadex.org/title/abc/solo-leveling") shouldBe "mangadex.org"
        hostOf("Solo Leveling") shouldBe null
        titleFromLink("https://site.com/manga/solo-leveling-123/") shouldBe "solo leveling"
        titleFromLink("https://site.com/series/one_piece") shouldBe "one piece"
        titleFromLink("Solo Leveling") shouldBe null
    }
}
