package tachiyomi.domain.anime

import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeFolder
import tachiyomi.domain.anime.model.AnimeStatus
import tachiyomi.domain.anime.model.AnimeTabMode
import tachiyomi.domain.anime.model.FolderSortOrder

/**
 * Regras das abas de anime (sem Android nem base de dados, para se poderem testar):
 * em que aba aparece cada pasta, a ordem dentro da pasta, a capa da pasta,
 * a ordem de cada aba (Recentes ou Manual) e as posições a gravar quando se arrasta.
 * Usado pela aba Anime da app (AnimeListViewModel) e pelo ecrã da pasta.
 */
object AnimeShelf {

    // Abaixo disto, a meia distância entre duas posições já não é segura: renumera-se a aba
    private const val MIN_POSITION_GAP = 1e-6

    /** Uma entrada da grelha de uma aba: um anime solto ou uma pasta com os seus animes. */
    sealed interface Item {
        val key: String
        val manualPosition: Double?
        val manualTab: AnimeStatus?

        // quando foi mexido pela última vez (modo Recentes)
        val touchedAt: Long
    }

    data class LooseAnime(val anime: Anime) : Item {
        override val key: String = animeKey(anime.malId)
        override val manualPosition: Double? = anime.manualPosition
        override val manualTab: AnimeStatus? = anime.manualTab
        override val touchedAt: Long = anime.touchedAt
    }

    /** [members] já vêm na ordem da pasta; [cover] é o anime cuja capa a pasta mostra (null se vazia). */
    data class Folder(val folder: AnimeFolder, val members: List<Anime>, val cover: Anime?) : Item {
        override val key: String = folderKey(folder.id)
        override val manualPosition: Double? = folder.manualPosition
        override val manualTab: AnimeStatus? = folder.manualTab

        // A data da pasta é a do anime dela mexido há menos tempo (mudar o nome ou a capa não conta)
        override val touchedAt: Long = members.maxOfOrNull { it.touchedAt } ?: 0L
    }

    fun animeKey(malId: Long): String = "anime:$malId"

    fun folderKey(folderId: String): String = "folder:$folderId"

    /**
     * Aba onde aparece uma pasta: todas Completed -> Completed; qualquer outra coisa (falta ver alguma)
     * -> Plan to Watch. Pasta vazia -> null: não aparece em nenhuma aba
     * (decisão do Afonso: pasta que fica vazia apaga-se sozinha; a app, o site e a edge function apagam-na).
     */
    fun folderTab(members: List<Anime>): AnimeStatus? {
        if (members.isEmpty()) {
            return null
        }
        val allCompleted = members.all { it.status == AnimeStatus.COMPLETED }
        if (allCompleted) {
            return AnimeStatus.COMPLETED
        }
        return AnimeStatus.PLAN_TO_WATCH
    }

    /**
     * Estado depois de mudar os episódios vistos: chegar ao total (se o MAL o souber) marca a temporada
     * como vista; descer de um Completed volta a Plan to Watch.
     */
    fun statusForEpisodes(anime: Anime, episodesWatched: Long): AnimeStatus {
        val total = anime.numEpisodes ?: return anime.status
        return if (episodesWatched >= total) AnimeStatus.COMPLETED else AnimeStatus.PLAN_TO_WATCH
    }

    /**
     * Animes da pasta pela ordem escolhida. Por data de estreia (o MAL não tem número de temporada,
     * por isso filmes e OVAs ficam entre as temporadas pela data); sem data vão para o fim.
     * Em MANUAL, pela posição à mão; os que não têm posição vão para o fim, pela data.
     */
    fun sortMembers(members: List<Anime>, sortOrder: FolderSortOrder): List<Anime> {
        val byDateAscending = members.sortedWith(compareBy(nullsLast()) { it.startDate })
        return when (sortOrder) {
            FolderSortOrder.DATE_ASC -> byDateAscending
            FolderSortOrder.DATE_DESC -> byDateAscending.reversed()
            FolderSortOrder.MANUAL -> byDateAscending.sortedWith(compareBy(nullsLast()) { it.folderPosition })
        }
    }

    /**
     * Anime cuja capa a pasta mostra: o escolhido ([AnimeFolder.coverMalId]) se ainda estiver lá dentro;
     * senão, a primeira temporada (a de estreia mais antiga). null se a pasta estiver vazia.
     */
    fun folderCover(folder: AnimeFolder, members: List<Anime>): Anime? {
        val chosenCover = members.find { it.malId == folder.coverMalId }
        if (chosenCover != null) {
            return chosenCover
        }
        return defaultCover(members)
    }

    /** Capa por defeito de uma pasta: a primeira temporada (estreia mais antiga). */
    fun defaultCover(members: List<Anime>): Anime? {
        return sortMembers(members, FolderSortOrder.DATE_ASC).firstOrNull()
    }

    /**
     * Anime aberto pelo botão ▶ da grelha (como o "continuar a ler" dos mangas): um anime solto se ainda
     * não estiver Completed; numa pasta, a temporada que se está a ver (começada e por acabar) e, se não
     * houver, a primeira por ver pela data de estreia. Tudo visto = null (sem botão).
     */
    fun animeToContinue(item: Item): Anime? = when (item) {
        is LooseAnime -> item.anime.takeIf { it.status != AnimeStatus.COMPLETED }
        is Folder -> animeToContinue(item.members)
    }

    fun animeToContinue(members: List<Anime>): Anime? {
        val notCompleted = sortMembers(members, FolderSortOrder.DATE_ASC).filter { it.status != AnimeStatus.COMPLETED }
        return notCompleted.firstOrNull { it.episodesWatched > 0 } ?: notCompleted.firstOrNull()
    }

    /** Pastas com os seus animes (na ordem de cada pasta) e a capa de cada uma. */
    fun buildFolders(animes: List<Anime>, folders: List<AnimeFolder>): List<Folder> {
        val membersByFolder = animes.filter { it.folderId != null }.groupBy { it.folderId }
        return folders.map { folder ->
            val members = membersByFolder[folder.id].orEmpty()
            val sortedMembers = sortMembers(members, folder.sortOrder)
            Folder(folder = folder, members = sortedMembers, cover = folderCover(folder, members))
        }
    }

    /**
     * Entradas da aba [tab]: os animes soltos com esse estado e as pastas cuja aba é essa.
     * Um anime que aponta para uma pasta que não existe (ainda não chegou) aparece solto.
     */
    fun itemsInTab(
        animes: List<Anime>,
        folders: List<AnimeFolder>,
        tab: AnimeStatus,
        mode: AnimeTabMode,
    ): List<Item> {
        val existingFolderIds = folders.map { it.id }.toSet()
        val looseAnimes = animes
            .filter { it.folderId == null || it.folderId !in existingFolderIds }
            .filter { it.status == tab }
            .map { LooseAnime(it) }
        val foldersInTab = buildFolders(animes, folders).filter { folderTab(it.members) == tab }
        return orderTab(looseAnimes + foldersInTab, tab, mode)
    }

    /**
     * Ordem de uma aba.
     * Recentes: o último mexido em cima.
     * Manual: primeiro o que ainda não foi arrumado nesta aba (veio de outra aba ou é novo), por Recentes;
     * depois o arrumado à mão, pela posição. Assim nada salta sozinho e o que entra vai para o topo.
     */
    fun orderTab(items: List<Item>, tab: AnimeStatus, mode: AnimeTabMode): List<Item> {
        val byRecent = items.sortedByDescending { it.touchedAt }
        if (mode == AnimeTabMode.RECENT) {
            return byRecent
        }
        val (arranged, notArranged) = byRecent.partition { hasManualPosition(it, tab) }
        return notArranged + arranged.sortedBy { it.manualPosition }
    }

    private fun hasManualPosition(item: Item, tab: AnimeStatus): Boolean {
        return item.manualTab == tab && item.manualPosition != null
    }

    /**
     * Posições a gravar depois de arrastar [movedKey] para a sua nova posição em [orderedItems] (já na ordem
     * nova). Se os vizinhos já estão arrumados à mão nesta aba e há espaço entre eles, só o arrastado muda
     * (fica a meio dos dois). Senão renumera a aba toda (0, 1, 2, ...), que é o que acontece no primeiro arrastar.
     * Devolve chave -> posição só das entradas cuja posição (ou aba) muda.
     */
    fun positionsAfterDrag(orderedItems: List<Item>, movedKey: String, tab: AnimeStatus): Map<String, Double> {
        val movedIndex = orderedItems.indexOfFirst { it.key == movedKey }
        if (movedIndex < 0) {
            return emptyMap()
        }

        // Os outros têm de estar arrumados nesta aba e pela mesma ordem que se vê (no modo Recentes as
        // posições antigas podem não bater certo com o que está no ecrã)
        val others = orderedItems.filter { it.key != movedKey }
        val othersArranged = others.all { hasManualPosition(it, tab) }
        if (othersArranged && isInIncreasingOrder(others)) {
            val previousPosition = orderedItems.getOrNull(movedIndex - 1)?.manualPosition
            val nextPosition = orderedItems.getOrNull(movedIndex + 1)?.manualPosition
            val newPosition = positionBetween(previousPosition, nextPosition)
            if (newPosition != null) {
                return mapOf(movedKey to newPosition)
            }
        }
        return renumber(orderedItems, tab)
    }

    /** Se as posições à mão (todas preenchidas) estão a subir pela ordem da lista. */
    private fun isInIncreasingOrder(items: List<Item>): Boolean {
        for (index in 1 until items.size) {
            val previousPosition = items[index - 1].manualPosition ?: return false
            val currentPosition = items[index].manualPosition ?: return false
            if (previousPosition >= currentPosition) {
                return false
            }
        }
        return true
    }

    /**
     * Posição entre [before] e [after] (null = sem vizinho desse lado).
     * Devolve null se não houver espaço seguro entre os dois (é preciso renumerar).
     */
    fun positionBetween(before: Double?, after: Double?): Double? {
        if (before == null && after == null) {
            return 0.0
        }
        if (before == null) {
            return after!! - 1.0
        }
        if (after == null) {
            return before + 1.0
        }
        val gap = after - before
        if (gap < MIN_POSITION_GAP) {
            return null
        }
        return before + gap / 2
    }

    /** Posições 0, 1, 2, ... pela ordem dada; só devolve as que mudam. */
    private fun renumber(orderedItems: List<Item>, tab: AnimeStatus): Map<String, Double> {
        val changedPositions = mutableMapOf<String, Double>()
        orderedItems.forEachIndexed { index, item ->
            val newPosition = index.toDouble()
            val alreadyThere = item.manualTab == tab && item.manualPosition == newPosition
            if (!alreadyThere) {
                changedPositions[item.key] = newPosition
            }
        }
        return changedPositions
    }

    /**
     * Posições à mão dentro de uma pasta depois de arrastar: 0, 1, 2, ... pela ordem nova.
     * (As pastas têm poucos animes, por isso renumera-se sempre.) Devolve mal_id -> posição das que mudam.
     */
    fun folderPositionsAfterDrag(orderedMembers: List<Anime>): Map<Long, Double> {
        val changedPositions = mutableMapOf<Long, Double>()
        orderedMembers.forEachIndexed { index, anime ->
            val newPosition = index.toDouble()
            if (anime.folderPosition != newPosition) {
                changedPositions[anime.malId] = newPosition
            }
        }
        return changedPositions
    }
}
