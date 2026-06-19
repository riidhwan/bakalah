package eu.kanade.tachiyomi.data.vault.localimport

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.vault.add.AddToVaultJobRunner
import eu.kanade.tachiyomi.data.vault.add.AddToVaultNotifier
import eu.kanade.tachiyomi.data.vault.capture.LibraryVaultCaptureJob
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import tachiyomi.domain.manga.interactor.GetManga
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
    private val notifier = AddToVaultNotifier(context)
    private val runner = AddToVaultJobRunner(getManga, repository)

    override suspend fun doWork(): Result {
        return runner.run(
            requestId = inputData.getLong(AddToVaultJobRunner.REQUEST_ID_KEY, -1L).takeIf { it != -1L },
            expectedWorkflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
            workflowName = "Local-to-Vault Import",
            setForeground = ::setForegroundSafely,
            showPreparing = { notifier.showPreparing(it) },
            showError = notifier::showError,
            showCancelled = notifier::showCancelled,
        ) { request, manga ->
            when (
                val result = importService.import(
                    localManga = manga,
                    importRequest = request,
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
                else -> Result.failure()
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
            val runner = AddToVaultJobRunner(Injekt.get(), Injekt.get())
            val requestId = runner.createRequest(
                mangaId = mangaId,
                workflow = VaultImportRequestWorkflow.LOCAL_IMPORT,
                selectedChapters = selectedChapters,
                targetMangaId = targetMangaId,
                createNewTitle = createNewTitle,
            )

            val request = AddToVaultJobRunner.buildWorkRequest(
                workerClass = LocalVaultImportJob::class.java,
                tag = TAG,
                mangaId = mangaId,
                requestId = requestId,
            )

            AddToVaultJobRunner.enqueueUnique(context, TAG, request)
            return true
        }
    }
}

private const val TAG = "LocalVaultImport"
