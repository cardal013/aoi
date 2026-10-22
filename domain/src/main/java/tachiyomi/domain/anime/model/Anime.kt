package tachiyomi.domain.anime.model

/** Anime da lista do utilizador, com os dados do AniList. */
data class Anime(
    val anilistId: Long,
    val title: String,
    val titleEnglish: String?,
    val coverUrl: String?,
    val format: String?,
    val episodes: Long?,
    val seasonYear: Long?,
    val status: AnimeStatus,
    val episodesWatched: Long = 0,
    val deleted: Boolean = false,
    val addedAt: Long = 0,
    val dirtyAt: Long? = null,
)

enum class AnimeStatus {
    WATCHING,
    PLAN_TO_WATCH,
    COMPLETED,
    ;

    companion object {
        fun fromRemote(value: String): AnimeStatus = entries.find { it.name == value.uppercase() } ?: PLAN_TO_WATCH
    }
}
