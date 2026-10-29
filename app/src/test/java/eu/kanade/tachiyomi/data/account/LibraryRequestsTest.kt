package eu.kanade.tachiyomi.data.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryRequestsTest {

    private fun candidate(title: String, source: String = "1") =
        RequestCandidate(sourceId = source, sourceName = "Fonte $source", url = "/$source/$title", title = title)

    // Título do MAL (romaji) e o inglês, como vêm do site
    private val names = listOf("Na Honjaman Level Up", "Solo Leveling")

    @Test
    fun `entra o único com o título igual a um dos nomes`() {
        val exact = candidate("Solo Leveling")
        val results = listOf(candidate("Solo Leveling: Ragnarok"), exact, candidate("Solo Max-Level Newbie"))
        pickRequestCandidate(names, results) shouldBe exact
        // Pontuação e maiúsculas não contam
        pickRequestCandidate(listOf("SOLO-LEVELING!"), results) shouldBe exact
    }

    @Test
    fun `um só resultado entra se o título for parecido`() {
        val similar = candidate("Solo Leveling (Official)")
        pickRequestCandidate(names, listOf(similar)) shouldBe similar
        pickRequestCandidate(names, listOf(candidate("Something else"))) shouldBe null
    }

    @Test
    fun `o mesmo título duas vezes pergunta`() {
        val results = listOf(candidate("Solo Leveling", "1"), candidate("Solo Leveling", "2"))
        pickRequestCandidate(names, results) shouldBe null
    }

    @Test
    fun `sem título igual pergunta`() {
        pickRequestCandidate(listOf("solo"), listOf(candidate("Solo Leveling"), candidate("Solo Max"))) shouldBe null
        pickRequestCandidate(listOf("solo"), emptyList()) shouldBe null
    }

    @Test
    fun `as opções começam pelas de título igual`() {
        val exact = candidate("Solo Leveling", "2")
        val other = candidate("Solo Leveling: Ragnarok", "1")
        sortCandidates(names, listOf(other, exact)) shouldBe listOf(exact, other)
    }
}
