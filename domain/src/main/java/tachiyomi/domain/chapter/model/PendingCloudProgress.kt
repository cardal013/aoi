package tachiyomi.domain.chapter.model

/** Progresso da cloud à espera de que o capítulo [chapterUrl] do manga [mangaId] apareça no aparelho. */
data class PendingCloudProgress(
    val mangaId: Long,
    val chapterUrl: String,
    val read: Boolean,
    val lastPageRead: Long,
)
