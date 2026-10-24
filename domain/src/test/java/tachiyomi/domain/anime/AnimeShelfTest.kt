package tachiyomi.domain.anime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeFolder
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.model.AnimeTabMode
import tachiyomi.domain.anime.model.FolderSortOrder

/** Regras das pastas e da ordem das abas de anime (plano da v0.23.0, nota mal-anime do vault). */
class AnimeShelfTest {

    private fun anime(
        malId: Long,
        status: AnimeStatus = AnimeStatus.WATCHING,
        startDate: String? = null,
        touchedAt: Long = 0,
        folderId: String? = null,
        folderPosition: Double? = null,
        manualPosition: Double? = null,
        manualTab: AnimeStatus? = null,
    ) = Anime(
        malId = malId,
        title = "Anime $malId",
        titleEnglish = null,
        coverUrl = "cover$malId",
        mediaType = "tv",
        numEpisodes = 12,
        startDate = startDate,
        status = status,
        touchedAt = touchedAt,
        folderId = folderId,
        folderPosition = folderPosition,
        manualPosition = manualPosition,
        manualTab = manualTab,
    )

    private fun folder(
        id: String,
        coverMalId: Long? = null,
        sortOrder: FolderSortOrder = FolderSortOrder.DATE_ASC,
        manualPosition: Double? = null,
        manualTab: AnimeStatus? = null,
    ) = AnimeFolder(
        id = id,
        name = "Pasta $id",
        coverMalId = coverMalId,
        sortOrder = sortOrder,
        manualPosition = manualPosition,
        manualTab = manualTab,
    )

    @Test
    fun `aba da pasta conforme o estado das temporadas`() {
        val completed = anime(1, AnimeStatus.COMPLETED)
        val planned = anime(2, AnimeStatus.PLAN_TO_WATCH)
        val watching = anime(3, AnimeStatus.WATCHING)

        AnimeShelf.folderTab(listOf(completed, completed.copy(malId = 4))) shouldBe AnimeStatus.COMPLETED
        AnimeShelf.folderTab(listOf(planned)) shouldBe AnimeStatus.PLAN_TO_WATCH
        AnimeShelf.folderTab(listOf(completed, planned)) shouldBe AnimeStatus.WATCHING
        AnimeShelf.folderTab(listOf(watching)) shouldBe AnimeStatus.WATCHING
        AnimeShelf.folderTab(emptyList()) shouldBe null
    }

    @Test
    fun `dentro da pasta ordena por data de estreia e sem data vai para o fim`() {
        val season2 = anime(2, startDate = "2023-01-05")
        val movie = anime(3, startDate = "2021-08")
        val season1 = anime(1, startDate = "2020-04-01")
        val unknown = anime(4, startDate = null)
        val members = listOf(season2, unknown, movie, season1)

        AnimeShelf.sortMembers(members, FolderSortOrder.DATE_ASC).map { it.malId } shouldBe listOf(1L, 3L, 2L, 4L)
        AnimeShelf.sortMembers(members, FolderSortOrder.DATE_DESC).map { it.malId } shouldBe listOf(4L, 2L, 3L, 1L)
    }

    @Test
    fun `ordem à mão dentro da pasta`() {
        val first = anime(1, startDate = "2020", folderPosition = 1.0)
        val second = anime(2, startDate = "2021", folderPosition = 0.0)
        val withoutPosition = anime(3, startDate = "2019")

        AnimeShelf.sortMembers(listOf(first, second, withoutPosition), FolderSortOrder.MANUAL).map { it.malId } shouldBe
            listOf(2L, 1L, 3L)
    }

    @Test
    fun `capa por defeito é a primeira temporada e a escolhida ganha enquanto estiver na pasta`() {
        val season1 = anime(1, startDate = "2020-04-01")
        val season2 = anime(2, startDate = "2023-01-05")

        AnimeShelf.folderCover(folder("p"), listOf(season2, season1))?.malId shouldBe 1L
        AnimeShelf.folderCover(folder("p", coverMalId = 2), listOf(season2, season1))?.malId shouldBe 2L
        // a capa escolhida saiu da pasta: volta à primeira temporada
        AnimeShelf.folderCover(folder("p", coverMalId = 9), listOf(season2, season1))?.malId shouldBe 1L
        AnimeShelf.folderCover(folder("p"), emptyList()) shouldBe null
    }

    @Test
    fun `pasta com uma temporada vista e outra por ver aparece em Watching e não solta`() {
        val season1 = anime(1, AnimeStatus.COMPLETED, folderId = "p")
        val season2 = anime(2, AnimeStatus.PLAN_TO_WATCH, folderId = "p")
        val loose = anime(3, AnimeStatus.COMPLETED)
        val animes = listOf(season1, season2, loose)
        val folders = listOf(folder("p"))

        val watching = AnimeShelf.itemsInTab(animes, folders, AnimeStatus.WATCHING, AnimeTabMode.RECENT)
        val completed = AnimeShelf.itemsInTab(animes, folders, AnimeStatus.COMPLETED, AnimeTabMode.RECENT)

        watching.map { it.key } shouldBe listOf("folder:p")
        (watching.single() as AnimeShelf.Folder).members.size shouldBe 2
        completed.map { it.key } shouldBe listOf("anime:3")
    }

    @Test
    fun `pasta vazia não aparece em nenhuma aba`() {
        val folders = listOf(folder("vazia"))

        for (tab in AnimeStatus.entries) {
            AnimeShelf.itemsInTab(emptyList(), folders, tab, AnimeTabMode.RECENT) shouldBe emptyList()
        }
    }

    @Test
    fun `anime numa pasta que não existe aparece solto`() {
        val orphan = anime(1, folderId = "apagada")

        AnimeShelf.itemsInTab(listOf(orphan), emptyList(), AnimeStatus.WATCHING, AnimeTabMode.RECENT)
            .map { it.key } shouldBe listOf("anime:1")
    }

    @Test
    fun `Recentes põe o último mexido em cima e a data da pasta é a do anime mais recente`() {
        val old = anime(1, touchedAt = 10)
        val inFolder = anime(2, touchedAt = 30, folderId = "p")
        val middle = anime(3, touchedAt = 20)

        AnimeShelf.itemsInTab(
            listOf(old, inFolder, middle),
            listOf(folder("p")),
            AnimeStatus.WATCHING,
            AnimeTabMode.RECENT,
        )
            .map { it.key } shouldBe listOf("folder:p", "anime:3", "anime:1")
    }

    @Test
    fun `Manual segue a posição e o que vem de outra aba vai para o topo`() {
        val first = anime(1, touchedAt = 1, manualPosition = 0.0, manualTab = AnimeStatus.WATCHING)
        val second = anime(2, touchedAt = 99, manualPosition = 1.0, manualTab = AnimeStatus.WATCHING)
        // arrumado à mão noutra aba: aqui conta como novo
        val fromOtherTab = anime(3, touchedAt = 5, manualPosition = 0.5, manualTab = AnimeStatus.PLAN_TO_WATCH)

        AnimeShelf.itemsInTab(
            listOf(first, second, fromOtherTab),
            emptyList(),
            AnimeStatus.WATCHING,
            AnimeTabMode.MANUAL,
        )
            .map { it.key } shouldBe listOf("anime:3", "anime:1", "anime:2")
    }

    @Test
    fun `primeiro arrastar renumera a aba toda`() {
        val items = listOf(anime(2), anime(1), anime(3)).map { AnimeShelf.LooseAnime(it) }

        AnimeShelf.positionsAfterDrag(items, "anime:2", AnimeStatus.WATCHING) shouldBe
            mapOf("anime:2" to 0.0, "anime:1" to 1.0, "anime:3" to 2.0)
    }

    @Test
    fun `arrastar numa aba já arrumada só muda o arrastado`() {
        val arranged = { malId: Long, position: Double ->
            AnimeShelf.LooseAnime(anime(malId, manualPosition = position, manualTab = AnimeStatus.WATCHING))
        }
        // o 3 (posição 2) foi arrastado para entre o 1 e o 2
        val newOrder = listOf(arranged(1, 0.0), arranged(3, 2.0), arranged(2, 1.0))

        AnimeShelf.positionsAfterDrag(newOrder, "anime:3", AnimeStatus.WATCHING) shouldBe mapOf("anime:3" to 0.5)
    }

    @Test
    fun `posição entre vizinhos`() {
        AnimeShelf.positionBetween(null, null) shouldBe 0.0
        AnimeShelf.positionBetween(null, 3.0) shouldBe 2.0
        AnimeShelf.positionBetween(3.0, null) shouldBe 4.0
        AnimeShelf.positionBetween(1.0, 2.0) shouldBe 1.5
        AnimeShelf.positionBetween(1.0, 1.0) shouldBe null
    }

    @Test
    fun `arrastar dentro da pasta renumera os animes`() {
        val members = listOf(anime(2, folderPosition = 1.0), anime(1, folderPosition = 0.0))

        AnimeShelf.folderPositionsAfterDrag(members) shouldBe mapOf(2L to 0.0, 1L to 1.0)
    }
}
