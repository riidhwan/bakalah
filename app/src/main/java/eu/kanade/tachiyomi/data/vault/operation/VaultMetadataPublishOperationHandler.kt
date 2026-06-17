package eu.kanade.tachiyomi.data.vault.operation

import eu.kanade.tachiyomi.data.vault.publishing.VaultMetadataPublishResult
import eu.kanade.tachiyomi.data.vault.publishing.VaultMetadataPublishService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.model.VaultTransferState
import tachiyomi.domain.vault.model.VaultTransferType

class VaultMetadataPublishOperationHandler(
    private val json: Json,
    private val publishService: VaultMetadataPublishService,
) : VaultOperationHandler {

    override val type: VaultTransferType = VaultTransferType.METADATA_PUBLISH
    override val policy: VaultOperationPolicy = VaultOperationPolicy.OptimisticBackgroundPublish

    override suspend fun execute(payloadJson: String): VaultOperationExecutionResult {
        val payload = try {
            json.decodeFromString<VaultMetadataPublishPayload>(payloadJson)
        } catch (_: SerializationException) {
            return VaultOperationExecutionResult(
                state = VaultTransferState.FAILED,
                failureReason = "invalid_payload",
            )
        }

        return when (val result = publishService.publish(payload.toPublishRequest())) {
            VaultMetadataPublishResult.Published -> VaultOperationExecutionResult(VaultTransferState.SUCCEEDED)
            else -> VaultOperationExecutionResult(
                state = VaultTransferState.FAILED,
                failureReason = result.toFailureReason(),
            )
        }
    }
}

fun VaultMetadataPublishResult.toFailureReason(): String {
    return when (this) {
        VaultMetadataPublishResult.Published -> "published"
        VaultMetadataPublishResult.TitleRequired -> "title_required"
        VaultMetadataPublishResult.IncompleteConfiguration -> "incomplete_configuration"
        VaultMetadataPublishResult.VaultNotFound -> "vault_not_found"
        VaultMetadataPublishResult.MangaNotFound -> "manga_not_found"
        VaultMetadataPublishResult.NotVault -> "not_vault"
        is VaultMetadataPublishResult.UnsupportedOlderVersion -> "unsupported_older_version"
        is VaultMetadataPublishResult.UnsupportedNewerVersion -> "unsupported_newer_version"
        is VaultMetadataPublishResult.IdentityChanged -> "identity_changed"
        is VaultMetadataPublishResult.RevisionMismatch -> "revision_mismatch"
        is VaultMetadataPublishResult.ManifestNotFound -> "manifest_not_found"
        is VaultMetadataPublishResult.IdentityMismatch -> "identity_mismatch"
        is VaultMetadataPublishResult.Malformed -> "malformed_manifest"
        VaultMetadataPublishResult.LabelNameConflict -> "label_name_conflict"
        VaultMetadataPublishResult.PublishFailed -> "publish_failed"
    }
}
