package eu.kanade.presentation.more.settings.screen.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.database.DatabaseViewerCell
import eu.kanade.tachiyomi.data.database.DatabaseViewerRow
import eu.kanade.tachiyomi.data.database.DatabaseViewerService
import eu.kanade.tachiyomi.data.database.DatabaseViewerTable
import eu.kanade.tachiyomi.data.database.DatabaseViewerTablePage
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DatabaseViewerScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { DatabaseViewerScreenModel() }
        val state by model.state.collectAsState()

        when (val s = state) {
            DatabaseViewerScreenModel.State.Loading -> LoadingScreen()
            is DatabaseViewerScreenModel.State.Ready -> {
                Scaffold(
                    topBar = { scrollBehavior ->
                        AppBar(
                            title = stringResource(MR.strings.pref_database_viewer),
                            navigateUp = navigator::pop,
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { contentPadding ->
                    if (s.tables.isEmpty()) {
                        EmptyScreen(
                            message = stringResource(MR.strings.database_viewer_no_tables),
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        LazyColumn(contentPadding = contentPadding) {
                            items(s.tables) { table ->
                                TableListItem(
                                    table = table,
                                    onClick = { navigator.push(DatabaseViewerTableScreen(table.name)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TableListItem(
        table: DatabaseViewerTable,
        onClick: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = table.name,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = table.rowCount?.let {
                    stringResource(MR.strings.database_viewer_row_count, it)
                } ?: stringResource(MR.strings.database_viewer_row_count_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

class DatabaseViewerTableScreen(
    private val tableName: String,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel(tableName) { DatabaseViewerTableScreenModel(tableName) }
        val state by model.state.collectAsState()

        when (val s = state) {
            DatabaseViewerTableScreenModel.State.Loading -> LoadingScreen()
            is DatabaseViewerTableScreenModel.State.Ready -> {
                s.selectedRow?.let { row ->
                    RowDetailDialog(
                        row = row,
                        onDismiss = model::hideRow,
                        onCopy = { cell ->
                            context.copyToClipboard(cell.column.name, cell.value.displayValue)
                        },
                    )
                }

                Scaffold(
                    topBar = { scrollBehavior ->
                        AppBar(
                            title = tableName,
                            navigateUp = navigator::pop,
                            actions = {
                                AppBarActions(
                                    actions = listOf(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.database_viewer_previous_page),
                                            icon = Icons.AutoMirrored.Outlined.NavigateBefore,
                                            enabled = s.page.hasPreviousPage,
                                            onClick = model::previousPage,
                                        ),
                                        AppBar.Action(
                                            title = stringResource(MR.strings.database_viewer_next_page),
                                            icon = Icons.AutoMirrored.Outlined.NavigateNext,
                                            enabled = s.page.hasNextPage,
                                            onClick = model::nextPage,
                                        ),
                                    ),
                                )
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { contentPadding ->
                    if (s.page.rows.isEmpty()) {
                        EmptyScreen(
                            message = stringResource(MR.strings.database_viewer_empty_table),
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = contentPadding + PaddingValues(bottom = 16.dp),
                        ) {
                            item {
                                PageLabel(page = s.page)
                            }
                            items(s.page.rows) { row ->
                                RowListItem(
                                    row = row,
                                    onClick = { model.showRow(row) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PageLabel(page: DatabaseViewerTablePage) {
        val end = page.offset + page.rows.size
        Text(
            text = stringResource(
                MR.strings.database_viewer_page_label,
                page.offset + 1,
                end,
                page.rowCount,
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun RowListItem(
        row: DatabaseViewerRow,
        onClick: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.summary.isNotBlank()) {
                Text(
                    text = row.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun RowDetailDialog(
        row: DatabaseViewerRow,
        onDismiss: () -> Unit,
        onCopy: (DatabaseViewerCell) -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = row.label) },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(row.cells) { cell ->
                        CellDetailItem(
                            cell = cell,
                            onCopy = { onCopy(cell) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
        )
    }

    @Composable
    private fun CellDetailItem(
        cell: DatabaseViewerCell,
        onCopy: () -> Unit,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = cell.column.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = cell.value.displayValue,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            HorizontalDivider()
        }
    }
}

private class DatabaseViewerScreenModel :
    StateScreenModel<DatabaseViewerScreenModel.State>(State.Loading) {

    private val databaseViewerService: DatabaseViewerService = Injekt.get()

    init {
        screenModelScope.launchIO {
            mutableState.update { State.Ready(databaseViewerService.getTables()) }
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(
            val tables: List<DatabaseViewerTable>,
        ) : State
    }
}

private class DatabaseViewerTableScreenModel(
    private val tableName: String,
) : StateScreenModel<DatabaseViewerTableScreenModel.State>(State.Loading) {

    private val databaseViewerService: DatabaseViewerService = Injekt.get()

    init {
        loadPage(offset = 0)
    }

    fun nextPage() {
        val state = state.value as? State.Ready ?: return
        if (state.page.hasNextPage) {
            loadPage(offset = state.page.offset + state.page.limit)
        }
    }

    fun previousPage() {
        val state = state.value as? State.Ready ?: return
        if (state.page.hasPreviousPage) {
            loadPage(offset = (state.page.offset - state.page.limit).coerceAtLeast(0))
        }
    }

    fun showRow(row: DatabaseViewerRow) = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selectedRow = row)
    }

    fun hideRow() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selectedRow = null)
    }

    private fun loadPage(offset: Long) {
        screenModelScope.launchIO {
            val page = databaseViewerService.getRows(tableName = tableName, offset = offset)
            mutableState.update { State.Ready(page = page) }
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(
            val page: DatabaseViewerTablePage,
            val selectedRow: DatabaseViewerRow? = null,
        ) : State
    }
}
