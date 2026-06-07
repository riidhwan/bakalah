package eu.kanade.tachiyomi.ui.vault

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
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import eu.kanade.presentation.vault.VaultMangaScreen as VaultMangaScreenContent

data class VaultMangaScreen(
    private val mangaId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val snackbarHostState = remember { SnackbarHostState() }
        val screenModel = rememberScreenModel { VaultMangaScreenModel(mangaId) }
        val state by screenModel.state.collectAsState()

        VaultMangaScreenContent(
            state = state,
            snackbarHostState = snackbarHostState,
            navigateUp = navigator::pop,
            onClickCache = {
                screenModel.reportUnavailable(VaultScreenModel.PendingAction.CACHE)
            },
            onClickEvict = {
                screenModel.reportUnavailable(VaultScreenModel.PendingAction.EVICT)
            },
            onClickRetry = {
                screenModel.reportUnavailable(VaultScreenModel.PendingAction.RETRY)
            },
        )

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    VaultMangaScreenModel.Event.LoadFailed ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    is VaultMangaScreenModel.Event.PendingActionUnavailable ->
                        snackbarHostState.showSnackbar(event.action.unavailableMessage(context))
                }
            }
        }
    }
}

fun VaultScreenModel.PendingAction.unavailableMessage(context: Context): String {
    val stringRes = when (this) {
        VaultScreenModel.PendingAction.CACHE -> MR.strings.vault_action_cache_unavailable
        VaultScreenModel.PendingAction.EVICT -> MR.strings.vault_action_evict_unavailable
        VaultScreenModel.PendingAction.RETRY -> MR.strings.vault_action_retry_unavailable
    }
    return context.stringResource(stringRes)
}
