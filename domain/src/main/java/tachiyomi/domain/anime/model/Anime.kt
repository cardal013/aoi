package tachiyomi.domain.anime.model

/**
 * Anime da lista do utilizador no MyAnimeList (cópia local da user_mal_anime do Supabase).
 * [touchedAt] é quando foi mexido pela última vez (modo Recentes); [dirtyAt] está preenchido enquanto
 * a alteração local não chega à edge function `mal` (fila offline).
 * [folderId], [folderPosition], [manualPosition] e [manualTab] são das pastas e da ordem à mão (só do Aoi).
 */
data class Anime(
    val malId: Long,
    val title: String,
    val titleEnglish: String?,
    val coverUrl: String?,
    val mediaType: String?,
    val numEpisodes: Long?,
    val startDate: String?,
    val status: AnimeStatus,
    val episodesWatched: Long = 0,
    val deleted: Boolean = false,
    val touchedAt: Long = 0,
    val folderId: String? = null,
    val folderPosition: Double? = null,
    val manualPosition: Double? = null,
    val manualTab: AnimeStatus? = null,
    val dirtyAt: Long? = null,
    // mudança de pasta ou de ordem à mão ainda por enviar ao Supabase
    val layoutDirtyAt: Long? = null,
)

/** Os 3 estados do Aoi. On Hold e Dropped do MAL passam a Plan to Watch no próprio MAL. */
enum class AnimeStatus {
    WATCHING,
    PLAN_TO_WATCH,
    COMPLETED,
    ;

    companion object {
        fun fromRemote(value: String): AnimeStatus = entries.find { it.name == value.uppercase() } ?: PLAN_TO_WATCH
    }
}
