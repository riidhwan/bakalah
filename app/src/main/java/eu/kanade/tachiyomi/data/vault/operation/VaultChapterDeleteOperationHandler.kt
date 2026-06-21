package eu.kanade.tachiyomi.data.vault.operation

import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterDeletionResult
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterDeletionService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType

class VaultChapterDeleteOperationHandler(
    private val json: Json,
    private val deletionService: VaultChapterDeletionService,
) : VaultOperationHandler {

    override val type: VaultTransferType = VaultTransferType.CHAPTER_DELETE
    override val policy: VaultOperationPolicy = VaultOperationPolicy.OptimisticBackgroundPublish

    override suspend fun execute(job: VaultTransferJob, payloadJson: String): VaultOperationExecutionResult {
        val payload = try {
            json.decodeFromString<VaultChapterDeletePayload>(payloadJson)
        } catch (_: SerializationException) {
            return VaultOperationExecutionResult(
                state = VaultTransferState.FAILED,
                failureReason = "invalid_payload",
            )
        }

        return when (
            val result = deletionService.delete(
                mangaId = payload.mangaId,
                chapterId = payload.chapterId,
                chapterIdentity = payload.chapterIdentity,
                ignoredJobId = job.id,
            )
        ) {
            VaultChapterDeletionResult.Deleted -> VaultOperationExecutionResult(VaultTransferState.SUCCEEDED)
            is VaultChapterDeletionResult.DeletedWithCleanupFailures -> VaultOperationExecutionResult(
                state = VaultTransferState.PARTIALLY_SUCCEEDED,
                failureReason = "cleanup_failed:${result.failedPaths.size}",
            )
            else -> VaultOperationExecutionResult(
                state = VaultTransferState.FAILED,
                failureReason = result.toFailureReason(),
            )
        }
    }
}

fun VaultChapterDeletionResult.toFailureReason(): String {
    return when (this) {
        VaultChapterDeletionResult.Deleted -> "deleted"
        is VaultChapterDeletionResult.DeletedWithCleanupFailures -> "cleanup_failed"
        VaultChapterDeletionResult.BlockedByActiveTransfer -> "active_transfer"
        VaultChapterDeletionResult.BlockedByActiveReader -> "active_reader"
        VaultChapterDeletionResult.LastChapter -> "last_chapter"
        VaultChapterDeletionResult.IncompleteConfiguration -> "incomplete_configuration"
        VaultChapterDeletionResult.VaultNotFound -> "vault_not_found"
        VaultChapterDeletionResult.MangaNotFound -> "manga_not_found"
        VaultChapterDeletionResult.ChapterNotFound -> "chapter_not_found"
        VaultChapterDeletionResult.NotVault -> "not_vault"
        is VaultChapterDeletionResult.UnsupportedOlderVersion -> "unsupported_older_version"
        is VaultChapterDeletionResult.UnsupportedNewerVersion -> "unsupported_newer_version"
        is VaultChapterDeletionResult.IdentityChanged -> "identity_changed"
        is VaultChapterDeletionResult.RevisionMismatch -> "revision_mismatch"
        is VaultChapterDeletionResult.ManifestNotFound -> "manifest_not_found"
        is VaultChapterDeletionResult.IdentityMismatch -> "identity_mismatch"
        is VaultChapterDeletionResult.Malformed -> "malformed_manifest"
        VaultChapterDeletionResult.PublishFailed -> "publish_failed"
    }
}
