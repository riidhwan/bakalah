package eu.kanade.tachiyomi.data.vault.operation

import eu.kanade.tachiyomi.data.vault.publishing.VaultLabelPublishEdit
import eu.kanade.tachiyomi.data.vault.publishing.VaultMetadataPublishRequest
import kotlinx.serialization.Serializable
import tachiyomi.domain.vault.model.VaultMangaStatus

@Serializable
data class VaultMetadataPublishPayload(
    val version: Int = VERSION,
    val mangaId: Long,
    val title: String,
    val author: String,
    val artist: String,
    val description: String,
    val status: VaultMangaStatus,
    val labels: List<VaultMetadataLabelEditPayload>,
) {
    fun toPublishRequest(): VaultMetadataPublishRequest {
        return VaultMetadataPublishRequest(
            mangaId = mangaId,
            title = title,
            author = author,
            artist = artist,
            description = description,
            status = status,
            labels = labels.map {
                VaultLabelPublishEdit(
                    identity = it.identity,
                    name = it.name,
                    isSensitive = it.isSensitive,
                    assigned = it.assigned,
                )
            },
        )
    }

    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class VaultMetadataLabelEditPayload(
    val identity: String?,
    val name: String,
    val isSensitive: Boolean,
    val assigned: Boolean,
)
