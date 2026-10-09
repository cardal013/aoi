package tachiyomi.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Triggers do sync incremental com a cloud (cloud_sync.sq): o que marca registos para enviar e as tombstones.
 */
class CloudSyncTriggersTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") })
        runBlocking { Database.Schema.create(driver).await() }
        db = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `novo favorito fica marcado e um manga só visto não`() {
        insertManga(id = 1, url = "/a", favorite = true)
        insertManga(id = 2, url = "/b", favorite = false)

        dirtyMangas() shouldBe listOf(1L)
    }

    @Test
    fun `só o favorito e o estado de leitura marcam o manga`() {
        insertManga(id = 1, url = "/a", favorite = true)
        exec("DELETE FROM cloud_dirty_mangas")

        exec("UPDATE mangas SET title = 'Outro', last_update = 123 WHERE _id = 1")
        dirtyMangas() shouldBe emptyList()

        exec("UPDATE mangas SET reading_status = 3 WHERE _id = 1")
        dirtyMangas() shouldBe listOf(1L)
    }

    @Test
    fun `só read e last_page_read marcam o capítulo`() {
        insertManga(id = 1, url = "/a", favorite = true)
        insertChapter(id = 10, mangaId = 1, read = false)
        dirtyChapters() shouldBe emptyList()

        exec("UPDATE chapters SET bookmark = 1, source_order = 5 WHERE _id = 10")
        dirtyChapters() shouldBe emptyList()

        exec("UPDATE chapters SET last_page_read = 4 WHERE _id = 10")
        dirtyChapters() shouldBe listOf(10L)
    }

    @Test
    fun `voltar a favoritar marca os capítulos com progresso`() {
        insertManga(id = 1, url = "/a", favorite = true)
        insertChapter(id = 10, mangaId = 1, read = true)
        insertChapter(id = 11, mangaId = 1, read = false)
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 1")
        exec("DELETE FROM cloud_dirty_chapters")

        exec("UPDATE mangas SET favorite = 1 WHERE _id = 1")
        dirtyChapters() shouldBe listOf(10L)
    }

    @Test
    fun `apagar um favorito deixa tombstone e voltar a inserir apaga-a`() {
        insertManga(id = 1, url = "/a", favorite = true)
        exec("DELETE FROM mangas WHERE _id = 1")

        tombstones() shouldBe listOf("1:/a")
        dirtyMangas() shouldBe emptyList()

        insertManga(id = 2, url = "/a", favorite = true)
        tombstones() shouldBe emptyList()
    }

    @Test
    fun `apagar uma remoção ainda por enviar deixa tombstone`() {
        insertManga(id = 1, url = "/a", favorite = true)
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 1")
        exec("DELETE FROM mangas WHERE _id = 1")

        tombstones() shouldBe listOf("1:/a")
    }

    @Test
    fun `apagar um manga que nunca foi favorito não deixa tombstone`() {
        insertManga(id = 1, url = "/a", favorite = false)
        exec("DELETE FROM mangas WHERE _id = 1")

        tombstones() shouldBe emptyList()
    }

    @Test
    fun `clearSyncedBefore só apaga o que é anterior ao início do upload`() = runBlocking<Unit> {
        insertManga(id = 1, url = "/a", favorite = true)
        insertManga(id = 2, url = "/b", favorite = true)
        exec("UPDATE cloud_dirty_mangas SET modified_at = 100 WHERE manga_id = 1")
        exec("UPDATE cloud_dirty_mangas SET modified_at = 300 WHERE manga_id = 2")

        db.cloud_syncQueries.clearSyncedBefore(200).await()
        dirtyMangas() shouldBe listOf(2L)
    }

    @Test
    fun `markDirty marca o manga e só os capítulos com progresso`() = runBlocking<Unit> {
        insertManga(id = 1, url = "/a", favorite = true)
        insertChapter(id = 10, mangaId = 1, read = true)
        insertChapter(id = 11, mangaId = 1, read = false)
        exec("DELETE FROM cloud_dirty_mangas")
        exec("DELETE FROM cloud_dirty_chapters")

        db.cloud_syncQueries.markDirtyMangas(500, listOf(1L))
        db.cloud_syncQueries.markDirtyChaptersOfMangas(500, listOf(1L))

        dirtyMangas() shouldBe listOf(1L)
        dirtyChapters() shouldBe listOf(10L)
    }

    private fun insertManga(id: Long, url: String, favorite: Boolean) = exec(
        "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, " +
            "cover_last_modified, date_added) " +
            "VALUES ($id, 1, '$url', 'Manga $id', 0, ${if (favorite) 1 else 0}, 0, 0, 0, 0, 0)",
    )

    private fun insertChapter(id: Long, mangaId: Long, read: Boolean) = exec(
        "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, " +
            "source_order, date_fetch, date_upload) " +
            "VALUES ($id, $mangaId, '/c$id', 'Cap $id', ${if (read) 1 else 0}, 0, 0, 1, 0, 0, 0)",
    )

    private fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun dirtyMangas() = longs("SELECT manga_id FROM cloud_dirty_mangas ORDER BY manga_id")

    private fun dirtyChapters() = longs("SELECT chapter_id FROM cloud_dirty_chapters ORDER BY chapter_id")

    private fun tombstones(): List<String> = driver.executeQuery(
        null,
        "SELECT source, url FROM cloud_removed_mangas ORDER BY url",
        { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) rows.add("${cursor.getLong(0)}:${cursor.getString(1)}")
            QueryResult.Value(rows)
        },
        0,
    ).value

    private fun longs(sql: String): List<Long> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            val rows = mutableListOf<Long>()
            while (cursor.next().value) rows.add(cursor.getLong(0)!!)
            QueryResult.Value(rows)
        },
        0,
    ).value
}
