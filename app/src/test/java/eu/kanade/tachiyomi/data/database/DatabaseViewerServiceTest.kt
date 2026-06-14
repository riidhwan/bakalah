package eu.kanade.tachiyomi.data.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DatabaseViewerServiceTest {

    @Test
    fun `lists app tables with row counts`() = runTest {
        val driver = FakeSqlDriver()
        val service = DatabaseViewerService(driver)

        service.getTables() shouldContainExactly listOf(
            DatabaseViewerTable(name = "mangas", rowCount = TABLE_ROW_COUNT),
        )

        driver.executedSql.first() shouldBe """
            SELECT name
            FROM sqlite_schema
            WHERE type = 'table'
            AND name NOT LIKE 'sqlite_%'
            AND name != 'android_metadata'
            ORDER BY name COLLATE NOCASE
        """.trimIndent()
    }

    @Test
    fun `rejects unknown table before querying rows`() = runTest {
        val driver = FakeSqlDriver()
        val service = DatabaseViewerService(driver)

        assertThrows<IllegalArgumentException> {
            service.getRows(tableName = "sqlite_sequence", offset = 0)
        }

        driver.executedSql.none { it.contains("sqlite_sequence") } shouldBe true
    }

    @Test
    fun `loads page rows with primary key label summaries and blob placeholders`() = runTest {
        val driver = FakeSqlDriver()
        val service = DatabaseViewerService(driver)

        val page = service.getRows(tableName = "mangas", offset = PAGE_OFFSET)

        page.offset shouldBe PAGE_OFFSET
        page.limit shouldBe DatabaseViewerService.DEFAULT_PAGE_SIZE
        page.rowCount shouldBe TABLE_ROW_COUNT
        page.rows.size shouldBe TABLE_ROW_COUNT.toInt()

        val row = page.rows.single()
        row.label shouldBe "_id=5"
        row.summary shouldBe EXPECTED_ROW_SUMMARY
        row.cells.map { it.value.displayValue } shouldContainExactly listOf(
            "5",
            LONG_TITLE,
            "<BLOB, 3 bytes>",
            "NULL",
        )

        driver.lastLimit shouldBe DatabaseViewerService.DEFAULT_PAGE_SIZE
        driver.lastOffset shouldBe PAGE_OFFSET
    }

    private class FakeSqlDriver : SqlDriver {
        val executedSql = mutableListOf<String>()
        var lastLimit: Long? = null
        var lastOffset: Long? = null

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            executedSql += sql
            val statement = FakePreparedStatement()
            binders?.invoke(statement)
            if (statement.longs.isNotEmpty()) {
                lastLimit = statement.longs[0]
                lastOffset = statement.longs[1]
            }

            return mapper(FakeCursor(rowsFor(sql)))
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = error("Writes are not supported")

        override fun newTransaction(): QueryResult<Transacter.Transaction> = error("Transactions are not supported")

        override fun currentTransaction(): Transacter.Transaction? = null

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

        override fun notifyListeners(vararg queryKeys: String) = Unit

        override fun close() = Unit

        private fun rowsFor(sql: String): List<List<Any?>> {
            return when {
                sql.contains("FROM sqlite_schema") -> listOf(listOf("mangas"))
                sql == "SELECT COUNT(*) FROM \"mangas\"" -> listOf(listOf(TABLE_ROW_COUNT))
                sql == "PRAGMA table_xinfo('mangas')" -> listOf(
                    columnInfo(name = "_id", type = "INTEGER", primaryKeyPosition = 1L),
                    columnInfo(name = "title", type = "TEXT"),
                    columnInfo(name = "cover", type = "BLOB"),
                    columnInfo(name = "notes", type = "TEXT"),
                )
                sql.startsWith("SELECT rowid, typeof(rowid), \"_id\"") -> listOf(
                    listOf(
                        MANGA_ID,
                        "integer",
                        MANGA_ID,
                        "integer",
                        LONG_TITLE,
                        "text",
                        BLOB_BYTES,
                        "blob",
                        null,
                        "null",
                    ),
                )
                else -> error("Unexpected SQL: $sql")
            }
        }

        private fun columnInfo(
            name: String,
            type: String,
            primaryKeyPosition: Long = 0,
        ): List<Any?> {
            return listOf(0L, name, type, 0L, null, primaryKeyPosition, 0L)
        }
    }

    private class FakePreparedStatement : SqlPreparedStatement {
        val longs = mutableMapOf<Int, Long?>()

        override fun bindBytes(index: Int, bytes: ByteArray?) = Unit

        override fun bindLong(index: Int, long: Long?) {
            longs[index] = long
        }

        override fun bindDouble(index: Int, double: Double?) = Unit

        override fun bindString(index: Int, string: String?) = Unit

        override fun bindBoolean(index: Int, boolean: Boolean?) = Unit
    }

    private class FakeCursor(
        private val rows: List<List<Any?>>,
    ) : SqlCursor {
        private var index = -1

        override fun next(): QueryResult<Boolean> = QueryResult.Value(++index < rows.size)

        override fun getString(index: Int): String? = value(index) as? String

        override fun getLong(index: Int): Long? = (value(index) as? Number)?.toLong()

        override fun getBytes(index: Int): ByteArray? = value(index) as? ByteArray

        override fun getDouble(index: Int): Double? = (value(index) as? Number)?.toDouble()

        override fun getBoolean(index: Int): Boolean? = value(index) as? Boolean

        private fun value(column: Int): Any? {
            check(index >= 0) { "Cursor is not positioned" }
            return rows[index][column]
        }
    }

    private companion object {
        const val TABLE_ROW_COUNT = 1L
        const val PAGE_OFFSET = 100L
        const val MANGA_ID = 5L
        const val LONG_TITLE =
            "This title is intentionally long enough to be shortened in row summaries for the database viewer"
        const val EXPECTED_ROW_SUMMARY =
            "_id=5  title=This title is intentionally long enough to be shortened in row summaries for...  " +
                "cover=<BLOB, 3 bytes>"

        val BLOB_BYTES = "abc".encodeToByteArray()
    }
}
