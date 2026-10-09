package eu.kanade.tachiyomi.data.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class CloudSyncRulesTest {

    private val source = 2499283573021220255L
    private val url = "/manga/one-piece"

    @Test
    fun `IDs remotos mantêm a fórmula de sempre`() {
        // Se isto falhar, tudo o que já está na cloud deixa de corresponder aos mangas locais
        mangaRemoteId(source, url) shouldBe "7e3f79d9-b25f-3b69-9163-057ee8256090"
        sourceRemoteId(source, url) shouldBe "25c16651-52a7-3b86-b6fa-79f2e90abe68"
        chapterRemoteId(source, url, "/chapter/1100") shouldBe "66b7f428-e4c5-3c77-ac07-df9e3caa95c4"
    }

    @Test
    fun `import completo nunca recua o progresso local`() {
        // Lido localmente, não lido na cloud: fica lido
        full(local = true to 0, remote = false to 0) shouldBe null
        // Página mais à frente na cloud: avança
        full(local = false to 3, remote = false to 10) shouldBe (false to 10L)
        // Página mais à frente localmente: fica
        full(local = false to 10, remote = false to 3) shouldBe null
        // Lido na cloud: passa a lido
        full(local = false to 5, remote = true to 0) shouldBe (true to 5L)
    }

    @Test
    fun `import incremental aplica o que vem da cloud`() {
        // Marcado como não lido noutro aparelho
        incremental(local = true to 20, remote = false to 0) shouldBe (false to 0L)
        incremental(local = false to 3, remote = false to 7) shouldBe (false to 7L)
        incremental(local = true to 7, remote = true to 7) shouldBe null
    }

    @Test
    fun `só locais exclui o que está na cloud e o que está por enviar`() {
        val inCloud = manga(1, "/a")
        val pending = manga(2, "/b")
        val localOnly = manga(3, "/c")
        val otherSource = manga(4, "/a", source = 1L)

        val remoteKeys = setOf(libraryKey(source, "/a"))
        val result = findLocalOnly(listOf(inCloud, pending, localOnly, otherSource), remoteKeys, pendingIds = setOf(2L))
        result shouldBe listOf(localOnly, otherSource)
    }

    private fun full(local: Pair<Boolean, Int>, remote: Pair<Boolean, Int>) = merge(local, remote, incremental = false)

    private fun incremental(local: Pair<Boolean, Int>, remote: Pair<Boolean, Int>) = merge(
        local,
        remote,
        incremental = true,
    )

    private fun merge(local: Pair<Boolean, Int>, remote: Pair<Boolean, Int>, incremental: Boolean) =
        mergeChapterProgress(local.first, local.second.toLong(), remote.first, remote.second.toLong(), incremental)

    private fun manga(id: Long, url: String, source: Long = this.source) =
        Manga.create().copy(id = id, url = url, source = source, favorite = true)
}
