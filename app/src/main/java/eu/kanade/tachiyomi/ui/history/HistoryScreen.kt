package eu.kanade.tachiyomi.ui.history

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.history.components.HistoryDeleteAllDialog
import eu.kanade.presentation.history.components.HistoryDeleteDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.flow.collectLatest
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistorySourceFilter
import tachiyomi.i18n.MR
import eu.kanade.presentation.history.HistoryScreen as HistoryScreenContent

class HistoryScreen private constructor(
    private val sourceFilterType: SourceFilterType,
    private val localSourceId: Long,
    private val title: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val snackbarHostState = remember { SnackbarHostState() }
        val sourceFilter = sourceFilterType.toHistorySourceFilter(localSourceId)
        val screenModel = rememberScreenModel { HistoryScreenModel(sourceFilter) }
        val state by screenModel.state.collectAsState()

        HistoryScreenContent(
            title = title,
            state = state,
            snackbarHostState = snackbarHostState,
            onSearchQueryChange = screenModel::updateSearchQuery,
            onClickCover = { navigator.push(MangaScreen(it)) },
            onClickResume = screenModel::getNextChapterForManga,
            onDialogChange = screenModel::setDialog,
            onClickFavorite = screenModel::addFavorite,
        )

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is HistoryScreenModel.Dialog.Delete -> {
                HistoryDeleteDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = { all ->
                        if (all) {
                            screenModel.removeAllFromHistory(dialog.history.mangaId)
                        } else {
                            screenModel.removeFromHistory(dialog.history)
                        }
                    },
                )
            }
            is HistoryScreenModel.Dialog.DeleteAll -> {
                HistoryDeleteAllDialog(
                    onDismissRequest = onDismissRequest,
                    onDelete = screenModel::removeAllHistory,
                )
            }
            is HistoryScreenModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.showMigrateDialog(dialog.manga, it) },
                )
            }
            is HistoryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is HistoryScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            null -> {}
        }

        LaunchedEffect(state.list) {
            if (state.list != null) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { e ->
                when (e) {
                    HistoryScreenModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    HistoryScreenModel.Event.HistoryCleared ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.clear_history_completed))
                    is HistoryScreenModel.Event.OpenChapter -> openChapter(context, snackbarHostState, e.chapter)
                }
            }
        }
    }

    private suspend fun openChapter(
        context: Context,
        snackbarHostState: SnackbarHostState,
        chapter: Chapter?,
    ) {
        if (chapter != null) {
            val intent = ReaderActivity.newIntent(context, chapter.mangaId, chapter.id)
            context.startActivity(intent)
        } else {
            snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
        }
    }

    private enum class SourceFilterType {
        Library,
        Local,
        Source,
    }

    private fun SourceFilterType.toHistorySourceFilter(localSourceId: Long): HistorySourceFilter {
        return when (this) {
            SourceFilterType.Library -> HistorySourceFilter.Library(excludedLocalSourceId = localSourceId)
            SourceFilterType.Local -> HistorySourceFilter.Local(localSourceId = localSourceId)
            SourceFilterType.Source -> HistorySourceFilter.Source(excludedLocalSourceId = localSourceId)
        }
    }

    companion object {
        fun library(excludedLocalSourceId: Long, title: String): HistoryScreen {
            return HistoryScreen(
                sourceFilterType = SourceFilterType.Library,
                localSourceId = excludedLocalSourceId,
                title = title,
            )
        }

        fun local(localSourceId: Long, title: String): HistoryScreen {
            return HistoryScreen(
                sourceFilterType = SourceFilterType.Local,
                localSourceId = localSourceId,
                title = title,
            )
        }

        fun source(excludedLocalSourceId: Long, title: String): HistoryScreen {
            return HistoryScreen(
                sourceFilterType = SourceFilterType.Source,
                localSourceId = excludedLocalSourceId,
                title = title,
            )
        }
    }
}
