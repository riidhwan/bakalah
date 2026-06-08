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
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
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
                screenModel.cacheChapter(it)
            },
            onClickEvict = {
                screenModel.evictChapter(it)
            },
            onClickRetry = {
                screenModel.retryChapter(it)
            },
            onClickRead = {
                context.startActivity(ReaderActivity.newVaultIntent(context, mangaId, it.chapter.id))
            },
            onClickDelete = {
                screenModel.deleteManga()
            },
            onClickSaveMetadata = {
                screenModel.publishMetadata(it)
            },
        )

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    VaultMangaScreenModel.Event.LoadFailed ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    VaultMangaScreenModel.Event.CacheFailed ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    VaultMangaScreenModel.Event.DeleteCompleted -> {
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.vault_delete_manga_complete))
                        navigator.pop()
                    }
                    is VaultMangaScreenModel.Event.DeleteFailed ->
                        snackbarHostState.showSnackbar(
                            context.stringResource(MR.strings.vault_delete_manga_failed_details, event.detail),
                        )
                    VaultMangaScreenModel.Event.MetadataPublished ->
                        snackbarHostState.showSnackbar(
                            context.stringResource(MR.strings.vault_metadata_publish_complete),
                        )
                    is VaultMangaScreenModel.Event.MetadataPublishFailed ->
                        snackbarHostState.showSnackbar(
                            context.stringResource(MR.strings.vault_metadata_publish_failed_details, event.detail),
                        )
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
        VaultScreenModel.PendingAction.DELETE -> MR.strings.vault_action_delete_unavailable
    }
    return context.stringResource(stringRes)
}
