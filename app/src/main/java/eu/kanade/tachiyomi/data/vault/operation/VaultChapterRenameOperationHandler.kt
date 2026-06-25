package eu.kanade.tachiyomi.data.vault.operation

import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterRenameResult
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterRenameService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.model.VaultTransferJob
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType

class VaultChapterRenameOperationHandler(
    private val json: Json,
    private val renameService: VaultChapterRenameService,
) : VaultOperationHandler {

    override val type: VaultTransferType = VaultTransferType.CHAPTER_RENAME
    override val policy: VaultOperationPolicy = VaultOperationPolicy.OptimisticBackgroundPublish

    override suspend fun execute(job: VaultTransferJob, payloadJson: String): VaultOperationExecutionResult {
        val payload = try {
            json.decodeFromString<VaultChapterRenamePayload>(payloadJson)
        } catch (_: SerializationException) {
            return VaultOperationExecutionResult(
                state = VaultTransferState.FAILED,
                failureReason = "invalid_payload",
            )
        }

        return when (
            val result = renameService.rename(
                mangaId = payload.mangaId,
                chapterId = payload.chapterId,
                chapterIdentity = payload.chapterIdentity,
                title = payload.title,
                ignoredJobId = job.id,
            )
        ) {
            VaultChapterRenameResult.Renamed -> VaultOperationExecutionResult(VaultTransferState.SUCCEEDED)
            else -> VaultOperationExecutionResult(
                state = VaultTransferState.FAILED,
                failureReason = result.toFailureReason(),
            )
        }
    }
}

fun VaultChapterRenameResult.toFailureReason(): String {
    return when (this) {
        VaultChapterRenameResult.Renamed -> "renamed"
        VaultChapterRenameResult.TitleRequired -> "title_required"
        VaultChapterRenameResult.BlockedByActiveTransfer -> "active_transfer"
        VaultChapterRenameResult.IncompleteConfiguration -> "incomplete_configuration"
        VaultChapterRenameResult.VaultNotFound -> "vault_not_found"
        VaultChapterRenameResult.MangaNotFound -> "manga_not_found"
        VaultChapterRenameResult.ChapterNotFound -> "chapter_not_found"
        VaultChapterRenameResult.ChapterIdentityMismatch -> "chapter_identity_mismatch"
        VaultChapterRenameResult.NotVault -> "not_vault"
        is VaultChapterRenameResult.UnsupportedOlderVersion -> "unsupported_older_version"
        is VaultChapterRenameResult.UnsupportedNewerVersion -> "unsupported_newer_version"
        is VaultChapterRenameResult.IdentityChanged -> "identity_changed"
        is VaultChapterRenameResult.RevisionMismatch -> "revision_mismatch"
        is VaultChapterRenameResult.ManifestNotFound -> "manifest_not_found"
        is VaultChapterRenameResult.IdentityMismatch -> "identity_mismatch"
        is VaultChapterRenameResult.Malformed -> "malformed_manifest"
        VaultChapterRenameResult.PublishFailed -> "publish_failed"
    }
}
