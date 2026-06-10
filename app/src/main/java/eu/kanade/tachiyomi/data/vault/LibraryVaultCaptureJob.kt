package eu.kanade.tachiyomi.data.vault

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibraryVaultCaptureJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val getManga: GetManga = Injekt.get()
    private val captureService: LibraryVaultCaptureService = Injekt.get()
    private val notifier = LocalVaultImportNotifier(context)

    override suspend fun doWork(): Result {
        val mangaId = inputData.getLong(MANGA_ID_KEY, -1L).takeIf { it != -1L } ?: return Result.failure()
        val selectedChapterIds = inputData.getStringArray(SELECTED_CHAPTER_IDS_KEY)
            ?.toSet()
            ?: return Result.failure()
        val confirmedDuplicateTitleKeys = inputData.getStringArray(CONFIRMED_DUPLICATE_TITLE_KEYS)
            ?.toSet()
            .orEmpty()
        val targetMangaId = inputData.getLong(TARGET_MANGA_ID_KEY, -1L).takeIf { it != -1L }
        val createNew = inputData.getBoolean(CREATE_NEW_KEY, false)
        val manga = getManga.await(mangaId) ?: return Result.failure()

        setForegroundSafely()
        notifier.showPreparing(manga.title)

        return withIOContext {
            try {
                when (
                    val result = captureService.capture(
                        manga = manga,
                        selectedChapterIds = selectedChapterIds,
                        confirmedDuplicateTitleKeys = confirmedDuplicateTitleKeys,
                        targetMangaId = targetMangaId,
                        createNew = createNew,
                        progress = notifier::showProgress,
                    )
                ) {
                    is LibraryVaultCaptureResult.Captured -> {
                        notifier.showCaptureComplete(
                            addedChapterCount = result.addedChapterCount,
                            replacedChapterCount = result.replacedChapterCount,
                            failedChapterCount = result.failedChapterCount,
                        )
                        if (result.addedChapterCount + result.replacedChapterCount > 0) {
                            Result.success()
                        } else {
                            Result.failure()
                        }
                    }
                    else -> {
                        notifier.showError()
                        Result.failure()
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Background Library-to-Vault Capture failed for mangaId=$mangaId" }
                notifier.showError()
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_VAULT_IMPORT_PROGRESS,
            notifier.showPreparing("").build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        fun isRunning(context: Context): Boolean = context.workManager.isRunning(TAG)

        fun startNow(
            context: Context,
            mangaId: Long,
            selectedChapterIds: Set<String>,
            confirmedDuplicateTitleKeys: Set<String>,
            targetMangaId: Long?,
            createNew: Boolean,
        ): Boolean {
            if (LocalVaultImportJob.isRunning(context) || isRunning(context)) return false

            val request = OneTimeWorkRequestBuilder<LibraryVaultCaptureJob>()
                .addTag(TAG)
                .addTag(tagFor(mangaId))
                .setInputData(
                    workDataOf(
                        MANGA_ID_KEY to mangaId,
                        SELECTED_CHAPTER_IDS_KEY to selectedChapterIds.toTypedArray(),
                        CONFIRMED_DUPLICATE_TITLE_KEYS to confirmedDuplicateTitleKeys.toTypedArray(),
                        TARGET_MANGA_ID_KEY to (targetMangaId ?: -1L),
                        CREATE_NEW_KEY to createNew,
                    ),
                )
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
            return true
        }

        private fun tagFor(mangaId: Long) = "$TAG:$mangaId"
    }
}

private const val TAG = "LibraryVaultCapture"
private const val MANGA_ID_KEY = "manga_id"
private const val SELECTED_CHAPTER_IDS_KEY = "selected_chapter_ids"
private const val CONFIRMED_DUPLICATE_TITLE_KEYS = "confirmed_duplicate_title_keys"
private const val TARGET_MANGA_ID_KEY = "target_manga_id"
private const val CREATE_NEW_KEY = "create_new"
