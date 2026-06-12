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
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultImportRequestChapter
import tachiyomi.domain.vault.model.VaultImportRequestWorkflow
import tachiyomi.domain.vault.repository.VaultRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalVaultImportJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val getManga: GetManga = Injekt.get()
    private val repository: VaultRepository = Injekt.get()
    private val importService: LocalVaultImportService = Injekt.get()
    private val notifier = LocalVaultImportNotifier(context)

    override suspend fun doWork(): Result {
        val requestId = inputData.getLong(REQUEST_ID_KEY, -1L).takeIf { it != -1L } ?: return Result.failure()
        val request = repository.getImportRequest(requestId) ?: return Result.failure()
        if (request.workflow != VaultImportRequestWorkflow.LOCAL_IMPORT) {
            repository.deleteImportRequest(requestId)
            return Result.failure()
        }
        val manga = getManga.await(request.mangaId) ?: run {
            repository.deleteImportRequest(requestId)
            return Result.failure()
        }

        setForegroundSafely()
        notifier.showPreparing(manga.title)

        return try {
            withIOContext {
                when (
                    val result = importService.import(
                        localManga = manga,
                        selectedChapterIds = request.selectedChapterIds,
                        allowedReplacementChapterIds = request.replacementChapterIds,
                        targetMangaId = request.targetMangaId,
                        createNew = request.createNew,
                        createNewTitle = request.createNewTitle,
                        progress = notifier::showProgress,
                    )
                ) {
                    is LocalVaultImportResult.Imported -> {
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
            }
        } catch (e: CancellationException) {
            notifier.showCancelled()
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Background Local-to-Vault Import failed for requestId=$requestId" }
            notifier.showError()
            Result.failure()
        } finally {
            repository.deleteImportRequest(requestId)
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
        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        suspend fun startNow(
            context: Context,
            mangaId: Long,
            selectedChapters: List<VaultImportRequestChapter>,
            targetMangaId: Long?,
            createNewTitle: String?,
        ): Boolean {
            if (isRunning(context) || LibraryVaultCaptureJob.isRunning(context)) return false
            val requestId = createRequest(
                mangaId = mangaId,
                selectedChapters = selectedChapters,
                targetMangaId = targetMangaId,
                createNewTitle = createNewTitle,
            )

            val request = OneTimeWorkRequestBuilder<LocalVaultImportJob>()
                .addTag(TAG)
                .addTag(tagFor(mangaId))
                .setInputData(
                    workDataOf(
                        REQUEST_ID_KEY to requestId,
                    ),
                )
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
            return true
        }

        private fun tagFor(mangaId: Long) = "$TAG:$mangaId"

        private suspend fun createRequest(
            mangaId: Long,
            selectedChapters: List<VaultImportRequestChapter>,
            targetMangaId: Long?,
            createNewTitle: String?,
        ): Long {
            val now = System.currentTimeMillis()
            return Injekt.get<VaultRepository>().insertImportRequest(
                VaultImportRequest(
                    id = -1,
                    mangaId = mangaId,
                    workflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
                    targetMangaId = targetMangaId,
                    createNewTitle = createNewTitle,
                    createdAt = now,
                    updatedAt = now,
                    chapters = selectedChapters,
                ),
            )
        }
    }
}

data class LocalVaultImportProgress(
    val current: Int,
    val total: Int,
    val chapterTitle: String?,
    val indeterminate: Boolean = false,
    val phase: VaultImportProgressPhase? = null,
)

enum class VaultImportProgressPhase {
    PREPARING,
    COPYING_DOWNLOADED,
    DOWNLOADING,
    COMPRESSING,
    UPLOADING,
    PUBLISHING,
    REFRESHING,
}

private const val TAG = "LocalVaultImport"
private const val REQUEST_ID_KEY = "request_id"
