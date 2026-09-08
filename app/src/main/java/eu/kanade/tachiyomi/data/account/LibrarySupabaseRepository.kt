package eu.kanade.tachiyomi.data.account

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.UUID
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.manga.model.ReadingStatus as DomainReadingStatus

@Inject
@SingleIn(AppScope::class)
class LibrarySupabaseRepository(
    private val supabase: SupabaseClient,
    private val mangaRepository: MangaRepository,
    private val sourceManager: SourceManager,
) {

    suspend fun getUserLibrary(userId: String): Result<List<UserLibraryItem>> {
        return try {
            // Nested query: fetches user_library entries and joins related tables in one go.
            val columns = Columns.raw("""
                *,
                manga(*),
                manga_sources(*, extensions(*)),
                chapters(*)
            """.trimIndent())

            val results = supabase.postgrest["user_library"]
                .select(columns = columns) {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<UserLibraryEntryRemote>()

            val items = results.map { entry ->
                UserLibraryItem(
                    mangaId = entry.mangaId,
                    title = entry.manga.title,
                    thumbnailUrl = entry.manga.thumbnailUrl,
                    status = entry.status,
                    isFavorite = entry.isFavorite,
                    extensionName = entry.source.extension.name,
                    sourceId = entry.source.extensionId.toLongOrNull() ?: -1L,
                    mangaUrl = entry.source.sourceMangaId,
                    lastChapterNumber = entry.lastChapter?.chapterNumber,
                    lastChapterLabel = entry.lastChapter?.chapterLabel,
                    lastPage = entry.lastPage,
                    lastReadAt = entry.lastReadAt
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Supabase: Error fetching user library" }
            Result.failure(e)
        }
    }

    /**
     * Groups the library items by their reading status for UI tabs.
     */
    fun groupByStatus(list: List<UserLibraryItem>): Map<String, List<UserLibraryItem>> {
        return list.groupBy { it.status }
    }

    suspend fun deleteAllLibrary(userId: String) {
        supabase.postgrest["user_library"].delete {
            filter { eq("user_id", userId) }
        }
    }

    suspend fun uploadMangaSync(userId: String, manga: Manga) {
        // Generate deterministic UUIDs based on source and url to avoid duplicates
        val remoteMangaId = UUID.nameUUIDFromBytes("manga:${manga.source}:${manga.url}".toByteArray()).toString()
        val remoteSourceId = UUID.nameUUIDFromBytes("source:${manga.source}:${manga.url}".toByteArray()).toString()
        val extensionId = manga.source.toString() // In Tachiyomi, source ID is the extension ID

        // 0. Ensure extension exists in 'extensions' table (required for FK in manga_sources)
        val source = sourceManager.getOrStub(manga.source)
        supabase.postgrest["extensions"].upsert(
            ExtensionRemote(
                id = extensionId,
                name = source.name,
                isActive = true
            )
        )

        // 1. Upsert into 'manga' table
        supabase.postgrest["manga"].upsert(
            MangaRemote(
                id = remoteMangaId,
                title = manga.title,
                author = manga.author,
                artist = manga.artist,
                thumbnailUrl = manga.thumbnailUrl
            )
        )

        // 2. Upsert into 'manga_sources' table
        supabase.postgrest["manga_sources"].upsert(
            MangaSourceSyncRemote(
                id = remoteSourceId,
                mangaId = remoteMangaId,
                extensionId = extensionId,
                sourceMangaId = manga.url // The URL is the unique ID within the source
            )
        )

        // 3. Upsert into 'user_library' table
        supabase.postgrest["user_library"].upsert(
            UserLibraryEntrySimple(
                userId = userId,
                mangaId = remoteMangaId,
                status = mapToRemoteStatus(manga.readingStatus),
                isFavorite = manga.favorite,
                sourceId = remoteSourceId,
                addedAt = java.time.Instant.now().toString()
            )
        )
    }

    private fun mapToRemoteStatus(status: DomainReadingStatus): String {
        return when (status) {
            DomainReadingStatus.READING -> "READING"
            DomainReadingStatus.COMPLETED -> "COMPLETED"
            DomainReadingStatus.DROPPED -> "DROPPED"
            DomainReadingStatus.PLAN_TO_READ -> "PLAN_TO_READ"
        }
    }

    suspend fun restoreRemoteMangaLocally(remote: UserLibraryItem) {
        val status = mapFromRemoteStatus(remote.status)
        val localManga = mangaRepository.getMangaByUrlAndSourceId(remote.mangaUrl, remote.sourceId)

        if (localManga != null) {
            // Update existing
            mangaRepository.update(
                tachiyomi.domain.manga.model.MangaUpdate(
                    id = localManga.id,
                    favorite = true,
                    readingStatus = status
                )
            )
        } else {
            // Create new from network info
            val newManga = Manga.create().copy(
                url = remote.mangaUrl,
                title = remote.title,
                source = remote.sourceId,
                favorite = true,
                thumbnailUrl = remote.thumbnailUrl,
                readingStatus = status,
                initialized = false
            )
            mangaRepository.insertNetworkManga(listOf(newManga))
        }
    }

    private fun mapFromRemoteStatus(remoteStatus: String): DomainReadingStatus {
        return when (remoteStatus.uppercase()) {
            "READING" -> DomainReadingStatus.READING
            "COMPLETED" -> DomainReadingStatus.COMPLETED
            "DROPPED" -> DomainReadingStatus.DROPPED
            "PLAN_TO_READ" -> DomainReadingStatus.PLAN_TO_READ
            else -> DomainReadingStatus.READING
        }
    }
}

@Serializable
data class UserLibraryEntrySimple(
    @SerialName("user_id") val userId: String,
    @SerialName("manga_id") val mangaId: String,
    val status: String,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("source_id") val sourceId: String,
    @SerialName("added_at") val addedAt: String
)

@Serializable
data class MangaSourceSyncRemote(
    val id: String,
    @SerialName("manga_id") val mangaId: String,
    @SerialName("extension_id") val extensionId: String,
    @SerialName("source_manga_id") val sourceMangaId: String
)

// --- Remote Data Models (Mapping Supabase Schema) ---

@Serializable
data class UserLibraryEntryRemote(
    @SerialName("user_id") val userId: String,
    @SerialName("manga_id") val mangaId: String,
    val status: String,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("source_id") val sourceId: String,
    @SerialName("last_chapter_id") val lastChapterId: String?,
    @SerialName("last_page") val lastPage: Int?,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    @SerialName("added_at") val addedAt: String? = null,

    // Joins
    val manga: MangaRemote,
    @SerialName("manga_sources") val source: MangaSourceRemote,
    @SerialName("chapters") val lastChapter: ChapterRemote? = null
)

@Serializable
data class MangaRemote(
    val id: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val status: String? = null,
)

@Serializable
data class MangaSourceRemote(
    val id: String,
    @SerialName("extension_id") val extensionId: String,
    @SerialName("source_manga_id") val sourceMangaId: String = "",
    @SerialName("extensions") val extension: ExtensionRemote
)

@Serializable
data class ExtensionRemote(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean
)

@Serializable
data class ChapterRemote(
    val id: String,
    @SerialName("chapter_number") val chapterNumber: Double?,
    @SerialName("chapter_label") val chapterLabel: String? = null,
    val scanlator: String? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null
)

// --- UI Model ---

@Serializable
data class UserLibraryItem(
    val mangaId: String,
    val title: String,
    val thumbnailUrl: String?,
    val status: String, // 'reading' | 'completed' | 'dropped' | 'plan_to_read'
    val isFavorite: Boolean,
    val extensionName: String,
    val sourceId: Long,
    val mangaUrl: String,
    val lastChapterNumber: Double?,
    val lastChapterLabel: String?,
    val lastPage: Int?,
    val lastReadAt: String?
)
