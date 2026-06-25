package eu.kanade.tachiyomi.ui.manga.local

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.local.LocalMangaDeletionResult
import eu.kanade.tachiyomi.data.local.LocalMangaDeletionService
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import kotlin.coroutines.cancellation.CancellationException

class MangaLocalDeletionCoordinator(
    private val localMangaDeletionService: LocalMangaDeletionService,
) {

    suspend fun delete(manga: Manga): MangaLocalDeletionOutcome {
        val result = try {
            localMangaDeletionService.delete(manga)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            LocalMangaDeletionResult.StateCleanupFailed
        }

        return when (result) {
            LocalMangaDeletionResult.Deleted -> MangaLocalDeletionOutcome.Deleted
            else -> MangaLocalDeletionOutcome.Failed(result.failureMessage)
        }
    }

    private val LocalMangaDeletionResult.failureMessage: StringResource
        get() = when (this) {
            LocalMangaDeletionResult.BlockedByActiveReader -> MR.strings.local_manga_delete_blocked_reader
            LocalMangaDeletionResult.BlockedByActiveImport -> MR.strings.local_manga_delete_blocked_import
            LocalMangaDeletionResult.MangaDirectoryNotFound -> MR.strings.local_manga_delete_missing_folder
            LocalMangaDeletionResult.FileDeletionFailed,
            LocalMangaDeletionResult.NotLocalManga,
            LocalMangaDeletionResult.StateCleanupFailed,
            -> MR.strings.local_manga_delete_failed
            LocalMangaDeletionResult.Deleted -> error("Deleted is not a failure")
        }
}

sealed interface MangaLocalDeletionOutcome {
    data object Deleted : MangaLocalDeletionOutcome
    data class Failed(val message: StringResource) : MangaLocalDeletionOutcome
}
