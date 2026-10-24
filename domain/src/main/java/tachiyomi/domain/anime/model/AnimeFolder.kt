package tachiyomi.domain.anime.model

/**
 * Pasta de animes (só do Aoi e do Supabase; o MAL não sabe delas). Não há pastas dentro de pastas.
 * [coverMalId] null = capa da primeira temporada. [manualPosition] só vale na aba [manualTab]
 * (a aba onde a pasta foi arrumada à mão). [dirtyAt] está preenchido enquanto a alteração não chega
 * ao Supabase; [deleted] marca uma pasta apagada aqui cuja remoção ainda não foi enviada.
 */
data class AnimeFolder(
    val id: String,
    val name: String,
    val coverMalId: Long?,
    val sortOrder: FolderSortOrder,
    val manualPosition: Double?,
    val manualTab: AnimeStatus?,
    val deleted: Boolean = false,
    val dirtyAt: Long? = null,
)

/** Ordem dentro da pasta: data de estreia crescente (por defeito), decrescente ou à mão. */
enum class FolderSortOrder {
    DATE_ASC,
    DATE_DESC,
    MANUAL,
    ;

    companion object {
        fun fromRemote(value: String): FolderSortOrder = entries.find { it.name == value } ?: DATE_ASC
    }
}

/** Modo de ordem de cada aba de anime: o último mexido em cima (por defeito) ou à mão. */
enum class AnimeTabMode {
    RECENT,
    MANUAL,
    ;

    companion object {
        fun fromRemote(value: String): AnimeTabMode = entries.find { it.name == value } ?: RECENT
    }
}
