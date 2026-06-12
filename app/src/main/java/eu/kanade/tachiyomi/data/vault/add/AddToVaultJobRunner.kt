package eu.kanade.tachiyomi.data.vault.add

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.workDataOf
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultImportRequestChapter
import tachiyomi.domain.vault.model.VaultImportRequestWorkflow
import tachiyomi.domain.vault.repository.VaultRepository

internal class AddToVaultJobRunner(
    private val getManga: GetManga,
    private val repository: VaultRepository,
) {

    suspend fun run(
        requestId: Long?,
        expectedWorkflow: VaultImportRequestWorkflow,
        workflowName: String,
        setForeground: suspend () -> Unit,
        showPreparing: (String) -> Unit,
        showError: () -> Unit,
        showCancelled: () -> Unit,
        runWorkflow: suspend (VaultImportRequest, Manga) -> ListenableWorker.Result,
    ): ListenableWorker.Result {
        requestId ?: return ListenableWorker.Result.failure()

        val request = repository.getImportRequest(requestId) ?: return ListenableWorker.Result.failure()
        if (request.workflow != expectedWorkflow) {
            repository.deleteImportRequest(requestId)
            return ListenableWorker.Result.failure()
        }

        val manga = getManga.await(request.mangaId) ?: run {
            repository.deleteImportRequest(requestId)
            return ListenableWorker.Result.failure()
        }

        setForeground()
        showPreparing(manga.title)

        return try {
            withIOContext {
                val result = runWorkflow(request, manga)
                if (result is ListenableWorker.Result.Failure) {
                    showError()
                }
                result
            }
        } catch (e: CancellationException) {
            showCancelled()
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Background $workflowName failed for requestId=$requestId" }
            showError()
            ListenableWorker.Result.failure()
        } finally {
            repository.deleteImportRequest(requestId)
        }
    }

    suspend fun createRequest(
        mangaId: Long,
        workflow: VaultImportRequestWorkflow,
        selectedChapters: List<VaultImportRequestChapter>,
        targetMangaId: Long?,
        createNewTitle: String?,
    ): Long {
        val now = System.currentTimeMillis()
        return repository.insertImportRequest(
            VaultImportRequest(
                id = -1,
                mangaId = mangaId,
                workflow = workflow,
                targetMangaId = targetMangaId,
                createNewTitle = createNewTitle,
                createdAt = now,
                updatedAt = now,
                chapters = selectedChapters,
            ),
        )
    }

    companion object {
        const val REQUEST_ID_KEY = "request_id"

        fun buildWorkRequest(
            workerClass: Class<out ListenableWorker>,
            tag: String,
            mangaId: Long,
            requestId: Long,
        ): OneTimeWorkRequest {
            return OneTimeWorkRequest.Builder(workerClass)
                .addTag(tag)
                .addTag(tagFor(tag, mangaId))
                .setInputData(
                    workDataOf(
                        REQUEST_ID_KEY to requestId,
                    ),
                )
                .build()
        }

        fun tagFor(tag: String, mangaId: Long) = "$tag:$mangaId"

        fun enqueueUnique(context: Context, tag: String, request: OneTimeWorkRequest) {
            context.workManager.enqueueUniqueWork(tag, ExistingWorkPolicy.KEEP, request)
        }
    }
}
