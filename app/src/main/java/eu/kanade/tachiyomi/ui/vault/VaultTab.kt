package eu.kanade.tachiyomi.ui.vault

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.vault.VaultScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object VaultTab : Tab {

    private val snackbarHostState = SnackbarHostState()

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_vault),
                icon = painterResource(R.drawable.ic_book_24dp),
            )
        }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { VaultScreenModel() }
        val state by screenModel.state.collectAsState()

        VaultScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onSearchQueryChange = screenModel::updateSearchQuery,
            onClickRefresh = screenModel::refreshVault,
            onClickManga = { navigator.push(VaultMangaScreen(it)) },
            onFilterChange = screenModel::setFilter,
            onSortChange = screenModel::setSort,
        )

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    VaultScreenModel.Event.LoadFailed ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    is VaultScreenModel.Event.RefreshCompleted ->
                        snackbarHostState.showSnackbar(
                            context.stringResource(
                                MR.strings.vault_refresh_completed_details,
                                event.mangaCount,
                                event.chapterCount,
                            ),
                        )
                    is VaultScreenModel.Event.RefreshFailed ->
                        snackbarHostState.showSnackbar(
                            context.stringResource(MR.strings.vault_refresh_failed_details, event.detail),
                        )
                    is VaultScreenModel.Event.PendingActionUnavailable ->
                        snackbarHostState.showSnackbar(event.action.unavailableMessage(context))
                }
            }
        }
    }
}
