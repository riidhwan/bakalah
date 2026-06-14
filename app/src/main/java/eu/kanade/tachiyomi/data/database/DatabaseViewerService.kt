package eu.kanade.tachiyomi.data.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

class DatabaseViewerService(
    private val driver: SqlDriver,
) {

    suspend fun getTables(): List<DatabaseViewerTable> {
        return queryList(
            sql = """
                SELECT name
                FROM sqlite_schema
                WHERE type = 'table'
                AND name NOT LIKE 'sqlite_%'
                AND name != 'android_metadata'
                ORDER BY name COLLATE NOCASE
            """.trimIndent(),
            parameters = 0,
        ) { cursor ->
            cursor.getString(0)!!
        }.map { tableName ->
            DatabaseViewerTable(
                name = tableName,
                rowCount = getRowCount(tableName),
            )
        }
    }

    suspend fun getRows(
        tableName: String,
        offset: Long,
        limit: Long = DEFAULT_PAGE_SIZE,
    ): DatabaseViewerTablePage {
        require(offset >= 0) { "offset must be non-negative" }
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }

        val tableNames = getTableNames().toSet()
        require(tableName in tableNames) { "Unknown table: $tableName" }

        val columns = getColumns(tableName)
        val rows = queryRows(tableName = tableName, columns = columns, offset = offset, limit = limit)

        return DatabaseViewerTablePage(
            tableName = tableName,
            columns = columns,
            rows = rows,
            offset = offset,
            limit = limit,
            rowCount = getRowCount(tableName) ?: 0,
        )
    }

    private suspend fun getTableNames(): List<String> {
        return queryList(
            sql = """
                SELECT name
                FROM sqlite_schema
                WHERE type = 'table'
                AND name NOT LIKE 'sqlite_%'
                AND name != 'android_metadata'
                ORDER BY name COLLATE NOCASE
            """.trimIndent(),
            parameters = 0,
        ) { cursor ->
            cursor.getString(0)!!
        }
    }

    private suspend fun getRowCount(tableName: String): Long? {
        return runCatching {
            queryOne(
                sql = "SELECT COUNT(*) FROM ${tableName.quoteIdentifier()}",
                parameters = 0,
            ) { cursor ->
                cursor.getLong(0) ?: 0
            }
        }.getOrNull()
    }

    private suspend fun getColumns(tableName: String): List<DatabaseViewerColumn> {
        return queryList(
            sql = "PRAGMA table_xinfo(${tableName.quoteStringLiteral()})",
            parameters = 0,
        ) { cursor ->
            val hidden = cursor.getLong(6) ?: 0
            if (hidden != 0L) return@queryList null

            DatabaseViewerColumn(
                name = cursor.getString(1)!!,
                type = cursor.getString(2).orEmpty(),
                primaryKeyPosition = cursor.getLong(5)?.toInt() ?: 0,
            )
        }.filterNotNull()
    }

    private suspend fun queryRows(
        tableName: String,
        columns: List<DatabaseViewerColumn>,
        offset: Long,
        limit: Long,
    ): List<DatabaseViewerRow> {
        if (columns.isEmpty()) return emptyList()

        val selectedColumns = buildList {
            add("rowid")
            add("typeof(rowid)")
            columns.forEach { column ->
                add(column.name.quoteIdentifier())
                add("typeof(${column.name.quoteIdentifier()})")
            }
        }
        val sql = """
            SELECT ${selectedColumns.joinToString()}
            FROM ${tableName.quoteIdentifier()}
            LIMIT ? OFFSET ?
        """.trimIndent()

        return queryList(
            sql = sql,
            parameters = 2,
            bind = {
                bindLong(0, limit)
                bindLong(1, offset)
            },
        ) { cursor ->
            val rowid = readCell(cursor = cursor, valueIndex = 0, typeIndex = 1)
            val cells = columns.mapIndexed { index, column ->
                val valueIndex = 2 + (index * 2)
                DatabaseViewerCell(
                    column = column,
                    value = readCell(cursor = cursor, valueIndex = valueIndex, typeIndex = valueIndex + 1),
                )
            }
            DatabaseViewerRow(
                label = rowLabel(rowid = rowid, cells = cells),
                summary = rowSummary(cells),
                cells = cells,
            )
        }
    }

    private fun readCell(
        cursor: SqlCursor,
        valueIndex: Int,
        typeIndex: Int,
    ): DatabaseViewerCellValue {
        return when (val type = cursor.getString(typeIndex)) {
            "null" -> DatabaseViewerCellValue(displayValue = "NULL")
            "integer" -> DatabaseViewerCellValue(displayValue = cursor.getLong(valueIndex).toString())
            "real" -> DatabaseViewerCellValue(displayValue = cursor.getDouble(valueIndex).toString())
            "blob" -> {
                val size = cursor.getBytes(valueIndex)?.size ?: 0
                DatabaseViewerCellValue(displayValue = "<BLOB, $size bytes>")
            }
            else -> DatabaseViewerCellValue(displayValue = cursor.getString(valueIndex).orEmpty())
        }
    }

    private fun rowLabel(
        rowid: DatabaseViewerCellValue,
        cells: List<DatabaseViewerCell>,
    ): String {
        val primaryKeyCells = cells
            .filter { it.column.primaryKeyPosition > 0 }
            .sortedBy { it.column.primaryKeyPosition }

        return if (primaryKeyCells.isNotEmpty()) {
            primaryKeyCells.joinToString { "${it.column.name}=${it.value.summaryValue}" }
        } else {
            "rowid=${rowid.summaryValue}"
        }
    }

    private fun rowSummary(cells: List<DatabaseViewerCell>): String {
        return cells
            .asSequence()
            .filterNot { it.value.displayValue == "NULL" }
            .map { "${it.column.name}=${it.value.summaryValue}" }
            .take(SUMMARY_CELL_COUNT)
            .joinToString(separator = "  ")
    }

    private suspend fun <T> queryOne(
        sql: String,
        parameters: Int,
        bind: SqlPreparedStatement.() -> Unit = {},
        mapper: (SqlCursor) -> T,
    ): T {
        return driver.executeQuery(null, sql, { cursor ->
            check(cursor.next().value) { "Query returned no rows" }
            QueryResult.Value(mapper(cursor))
        }, parameters, bind).await()
    }

    private suspend fun <T> queryList(
        sql: String,
        parameters: Int,
        bind: SqlPreparedStatement.() -> Unit = {},
        mapper: (SqlCursor) -> T,
    ): List<T> {
        return driver.executeQuery(null, sql, { cursor ->
            val rows = mutableListOf<T>()
            while (cursor.next().value) {
                rows += mapper(cursor)
            }
            QueryResult.Value(rows)
        }, parameters, bind).await()
    }

    private fun String.quoteIdentifier(): String {
        return "\"${replace("\"", "\"\"")}\""
    }

    private fun String.quoteStringLiteral(): String {
        return "'${replace("'", "''")}'"
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100L
        const val SUMMARY_VALUE_LENGTH = 80

        private const val MAX_PAGE_SIZE = 500L
        private const val SUMMARY_CELL_COUNT = 3
    }
}

data class DatabaseViewerTable(
    val name: String,
    val rowCount: Long?,
)

data class DatabaseViewerTablePage(
    val tableName: String,
    val columns: List<DatabaseViewerColumn>,
    val rows: List<DatabaseViewerRow>,
    val offset: Long,
    val limit: Long,
    val rowCount: Long,
) {
    val hasPreviousPage: Boolean
        get() = offset > 0

    val hasNextPage: Boolean
        get() = offset + rows.size < rowCount
}

data class DatabaseViewerColumn(
    val name: String,
    val type: String,
    val primaryKeyPosition: Int,
)

data class DatabaseViewerRow(
    val label: String,
    val summary: String,
    val cells: List<DatabaseViewerCell>,
)

data class DatabaseViewerCell(
    val column: DatabaseViewerColumn,
    val value: DatabaseViewerCellValue,
)

data class DatabaseViewerCellValue(
    val displayValue: String,
) {
    val summaryValue: String
        get() = displayValue.truncateForSummary()
}

private fun String.truncateForSummary(): String {
    return if (length <= DatabaseViewerService.SUMMARY_VALUE_LENGTH) {
        this
    } else {
        take(DatabaseViewerService.SUMMARY_VALUE_LENGTH - 3).trimEnd() + "..."
    }
}
