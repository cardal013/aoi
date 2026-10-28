package eu.kanade.tachiyomi.data.anime

import java.text.Normalizer

/**
 * Compara o título de um anime do MAL com os resultados de uma fonte, para escolher sozinho o certo.
 * "2nd Season" e "Season 2" contam como iguais; pontuação, acentos e maiúsculas não contam.
 */
object AnimeMatch {

    /** Acima disto o melhor resultado escolhe-se sem perguntar. */
    const val AUTO_PICK = 0.8

    private val ORDINAL_SEASON = Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b""")
    private val SEASON_N = Regex("""\bseason\s+(\d+)\b""")
    private val PART_N = Regex("""\bpart\s+(\d+)\b""")
    private val NOT_ALNUM = Regex("""[^a-z0-9]+""")
    private val ACCENTS = Regex("""\p{Mn}+""")

    fun normalize(title: String): String {
        val plain = ACCENTS.replace(Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD), "")
        return NOT_ALNUM.replace(plain, " ")
            .replace(ORDINAL_SEASON, "s$1")
            .replace(SEASON_N, "s$1")
            .replace(PART_N, "p$1")
            .replace(Regex("""\bs1\b"""), "")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /** 0..1: quão parecido é [candidate] com o melhor dos [titles] (título, título em inglês). */
    fun score(titles: List<String>, candidate: String): Double {
        val cand = normalize(candidate)
        if (cand.isEmpty()) return 0.0
        return titles.map(::normalize).filter { it.isNotEmpty() }.maxOfOrNull { title ->
            var s = if (title == cand) 1.0 else dice(title, cand)
            // Dobrado ou outra temporada que não se pediu
            if (has(cand, "dub") && !has(title, "dub")) s -= 0.15
            if (seasons(cand) != seasons(title)) s -= 0.3
            s
        }?.coerceIn(0.0, 1.0) ?: 0.0
    }

    private fun has(text: String, word: String) = text.split(' ').contains(word)

    private fun seasons(text: String): Set<String> =
        text.split(' ').filter { it.length > 1 && (it[0] == 's' || it[0] == 'p') && it.drop(1).all(Char::isDigit) }.toSet()

    private fun dice(a: String, b: String): Double {
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val counts = y.groupingBy { it }.eachCount().toMutableMap()
        var common = 0
        for (g in x) {
            val c = counts[g] ?: 0
            if (c > 0) {
                common++
                counts[g] = c - 1
            }
        }
        return 2.0 * common / (x.size + y.size)
    }

    private fun bigrams(text: String): List<String> {
        val s = text.replace(" ", "")
        return (0 until s.length - 1).map { s.substring(it, it + 2) }
    }
}
