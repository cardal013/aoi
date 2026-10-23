package tachiyomi.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
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

    @Test
    fun `progresso pendente da cloud aplica-se quando o capítulo chega e não fica para enviar`() {
        insertManga(id = 1, url = "/a", favorite = true)
        exec("INSERT INTO cloud_pending_progress VALUES (1, '/c10', 1, 7), (1, '/c11', 0, 3)")

        insertChapter(id = 10, mangaId = 1, read = false)
        insertChapter(id = 11, mangaId = 1, read = false)
        insertChapter(id = 12, mangaId = 1, read = false)

        longs("SELECT read FROM chapters ORDER BY _id") shouldBe listOf(1L, 0L, 0L)
        longs("SELECT last_page_read FROM chapters ORDER BY _id") shouldBe listOf(7L, 3L, 0L)
        longs("SELECT count(*) FROM cloud_pending_progress") shouldBe listOf(0L)
        dirtyChapters() shouldBe emptyList()
    }

    @Test
    fun `progresso pendente nunca recua o capítulo`() {
        insertManga(id = 1, url = "/a", favorite = true)
        exec("INSERT INTO cloud_pending_progress VALUES (1, '/c10', 0, 2)")

        insertChapter(id = 10, mangaId = 1, read = true)

        longs("SELECT read FROM chapters") shouldBe listOf(1L)
        longs("SELECT last_page_read FROM chapters") shouldBe listOf(2L)
        // Já tinha progresso ao ser inserido: continua por enviar
        dirtyChapters() shouldBe listOf(10L)
    }

    @Test
    fun `migration 18 cria os animes e o progresso pendente`() = runBlocking<Unit> {
        exec("DROP TRIGGER cloud_pending_progress_on_chapter_insert")
        exec("DROP TABLE cloud_pending_progress")

        Database.Schema.migrate(driver, 18, 19).await()

        insertManga(id = 1, url = "/a", favorite = true)
        exec("INSERT INTO cloud_pending_progress VALUES (1, '/c10', 1, 0)")
        insertChapter(id = 10, mangaId = 1, read = false)
        longs("SELECT read FROM chapters") shouldBe listOf(1L)
        exec("INSERT INTO anime(anilist_id, title, status, added_at) VALUES (1, 'Frieren', 'WATCHING', 0)")
    }

    @Test
    fun `migration 19 deita fora os animes do AniList e cria a lista do MAL`() = runBlocking<Unit> {
        exec("DROP TRIGGER cloud_pending_progress_on_chapter_insert")
        exec("DROP TABLE cloud_pending_progress")
        exec("DROP TABLE mal_anime")
        Database.Schema.migrate(driver, 18, 19).await()
        exec("INSERT INTO anime(anilist_id, title, status, added_at) VALUES (1, 'Frieren', 'WATCHING', 0)")

        Database.Schema.migrate(driver, 19, 20).await()

        longs("SELECT count(*) FROM sqlite_master WHERE name = 'anime'") shouldBe listOf(0L)
        exec(
            "INSERT INTO mal_anime(user_id, mal_id, title, status, touched_at, dirty_at) " +
                "VALUES ('u1', 52991, 'Frieren', 'WATCHING', 5, 5)",
        )
        longs("SELECT mal_id FROM mal_anime") shouldBe listOf(52991L)
    }

    @Test
    fun `animes do MAL não acordam o upload dos mangas`() {
        exec(
            "INSERT INTO mal_anime(user_id, mal_id, title, status, touched_at, dirty_at) " +
                "VALUES ('u1', 52991, 'Frieren', 'WATCHING', 5, 5)",
        )
        runBlocking { db.cloud_syncQueries.lastLocalChange().awaitAsOne().last } shouldBe 0L
    }

    @Test
    fun `remover um anime do MAL tira-o da pasta e cada conta tem a sua lista`() = runBlocking<Unit> {
        exec(
            "INSERT INTO mal_anime(user_id, mal_id, title, status, touched_at, folder_id, manual_position) " +
                "VALUES ('u1', 1, 'A', 'WATCHING', 1, 'pasta', 2.0)",
        )
        exec(
            "INSERT INTO mal_anime(user_id, mal_id, title, status, touched_at) VALUES ('u2', 1, 'A', 'WATCHING', 1)",
        )

        db.mal_animeQueries.markDeleted(now = 9, userId = "u1", malId = 1)

        val leftFolder = "SELECT count(*) FROM mal_anime " +
            "WHERE folder_id IS NULL AND manual_position IS NULL AND deleted = 1"
        longs(leftFolder) shouldBe listOf(1L)
        db.mal_animeQueries.getAll("u2").awaitAsList().size shouldBe 1
        db.mal_animeQueries.getAll("u1").awaitAsList().size shouldBe 0
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
