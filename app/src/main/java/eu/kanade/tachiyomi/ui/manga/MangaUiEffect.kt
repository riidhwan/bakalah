package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

internal sealed interface MangaUiEffect {
    data class ShowSnackbar(
        val message: String,
        val actionLabel: String? = null,
        val withDismissAction: Boolean = false,
        val duration: SnackbarDuration = SnackbarDuration.Short,
        val action: MangaSnackbarAction? = null,
    ) : MangaUiEffect

    data class ShowToast(val message: String) : MangaUiEffect
}

internal sealed interface MangaSnackbarAction {
    data object AddToLibrary : MangaSnackbarAction
    data object DeleteDownloads : MangaSnackbarAction
    data class ConfirmTrackerUpdate(val update: MangaTrackingUpdate.Prompt) : MangaSnackbarAction
}

internal class MangaUiEffectFactory(
    private val context: Context,
) {
    fun snackbar(message: String): MangaUiEffect {
        return MangaUiEffect.ShowSnackbar(message = message)
    }

    fun addToLibraryPrompt(): MangaUiEffect {
        return MangaUiEffect.ShowSnackbar(
            message = context.stringResource(MR.strings.snack_add_to_library),
            actionLabel = context.stringResource(MR.strings.action_add),
            withDismissAction = true,
            action = MangaSnackbarAction.AddToLibrary,
        )
    }

    fun deleteDownloadsPrompt(): MangaUiEffect {
        return MangaUiEffect.ShowSnackbar(
            message = context.stringResource(MR.strings.delete_downloads_for_manga),
            actionLabel = context.stringResource(MR.strings.action_delete),
            withDismissAction = true,
            action = MangaSnackbarAction.DeleteDownloads,
        )
    }

    fun trackerRefreshFailure(failure: MangaTrackerRefreshFailure): MangaUiEffect {
        return MangaUiEffect.ShowToast(
            context.stringResource(
                MR.strings.track_error,
                failure.trackerName,
                failure.error.message ?: "",
            ),
        )
    }

    fun trackerUpdated(chapterNumber: Int): MangaUiEffect {
        return MangaUiEffect.ShowToast(
            context.stringResource(
                MR.strings.trackers_updated_summary,
                chapterNumber,
            ),
        )
    }

    fun confirmTrackerUpdate(update: MangaTrackingUpdate.Prompt): MangaUiEffect {
        return MangaUiEffect.ShowSnackbar(
            message = context.stringResource(
                MR.strings.confirm_tracker_update,
                update.chapterNumber.toInt(),
            ),
            actionLabel = context.stringResource(MR.strings.action_ok),
            duration = SnackbarDuration.Short,
            withDismissAction = true,
            action = MangaSnackbarAction.ConfirmTrackerUpdate(update),
        )
    }

    fun chapterSettingsUpdated(): MangaUiEffect {
        return snackbar(context.stringResource(MR.strings.chapter_settings_updated))
    }

    fun localMangaDeleteComplete(): MangaUiEffect {
        return MangaUiEffect.ShowToast(context.stringResource(MR.strings.local_manga_delete_complete))
    }
}
