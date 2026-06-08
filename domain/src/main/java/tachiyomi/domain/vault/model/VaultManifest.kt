package tachiyomi.domain.vault.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val CONTENT_VAULT_APP_ID = "bakalah-content-vault"
const val CURRENT_VAULT_LAYOUT_VERSION = 2L
const val ROOT_VAULT_MANIFEST_NAME = "content-vault.json"

private const val MIN_SUPPORTED_VAULT_LAYOUT_VERSION = 1L

@Serializable
data class VaultRootManifest(
    val app: String = CONTENT_VAULT_APP_ID,
    @SerialName("contentVaultIdentity")
    val identity: String,
    val displayName: String,
    val layoutVersion: Long,
    val revisionId: String,
    val revisionNumber: Long,
    val writerId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val summary: VaultCatalogueSummary = VaultCatalogueSummary(),
    val manga: List<VaultMangaManifestPointer> = emptyList(),
)

@Serializable
data class VaultCatalogueSummary(
    val mangaCount: Long = 0,
    val chapterCount: Long = 0,
    val labelCount: Long = 0,
    val updatedAt: Long? = null,
)

@Serializable
data class VaultMangaManifestPointer(
    val identity: String,
    val path: String,
    val title: String,
    val revisionId: String,
    val revisionNumber: Long,
    val updatedAt: Long,
)

@Serializable
data class VaultMangaManifest(
    val app: String = CONTENT_VAULT_APP_ID,
    val layoutVersion: Long,
    val vaultIdentity: String,
    val mangaIdentity: String,
    val revisionId: String,
    val revisionNumber: Long,
    val metadata: VaultManifestMetadata,
    val labels: List<VaultManifestLabel> = emptyList(),
    val cover: VaultManifestCover? = null,
    val chapters: List<VaultManifestChapter> = emptyList(),
    val provenance: VaultManifestProvenance = VaultManifestProvenance(),
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class VaultManifestMetadata(
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val status: VaultMangaStatus = VaultMangaStatus.UNKNOWN,
)

@Serializable
data class VaultManifestLabel(
    val identity: String,
    val name: String,
    val sortKey: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class VaultManifestCover(
    val identity: String,
    val path: String,
    val mediaType: String? = null,
    val integrity: VaultContentIntegrity? = null,
    val revisionId: String,
    val revisionNumber: Long,
    val updatedAt: Long,
)

@Serializable
data class VaultManifestChapter(
    val identity: String,
    val title: String,
    val chapterNumber: Double,
    val volumeNumber: Double? = null,
    val scanlator: String? = null,
    val sourceOrder: Long,
    val content: VaultManifestChapterContent,
    val revisionId: String,
    val revisionNumber: Long,
    val dateUpload: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class VaultManifestChapterContent(
    val path: String,
    val format: VaultChapterContentFormat,
    val integrity: VaultContentIntegrity,
)

@Serializable
data class VaultContentIntegrity(
    val sizeBytes: Long,
    val checksumSha256: String,
)

@Serializable
data class VaultManifestProvenance(
    val importedFrom: String? = null,
    val sourceName: String? = null,
    val sourceUri: String? = null,
    val importedAt: Long? = null,
)

class VaultManifestCodec(
    private val json: Json,
) {
    fun encodeRoot(manifest: VaultRootManifest): String = json.encodeToString(manifest)

    fun encodeManga(manifest: VaultMangaManifest): String = json.encodeToString(manifest)

    fun decodeRoot(body: String): VaultManifestReadResult<VaultRootManifest> {
        return decode(body) {
            json.decodeFromString<LegacyVaultRootManifest>(body).toRootManifest()
        }
    }

    fun decodeManga(body: String): VaultManifestReadResult<VaultMangaManifest> {
        return decode(body) { json.decodeFromString<VaultMangaManifest>(body) }
    }

    private fun <T : Any> decode(
        body: String,
        block: () -> T,
    ): VaultManifestReadResult<T> {
        return runCatching(block)
            .fold(
                onSuccess = { manifest ->
                    when (val compatibility = manifest.compatibility()) {
                        VaultManifestCompatibility.Current -> VaultManifestReadResult.Success(manifest)
                        VaultManifestCompatibility.NotVault -> VaultManifestReadResult.NotVault
                        is VaultManifestCompatibility.UnsupportedOlder ->
                            VaultManifestReadResult.UnsupportedOlderVersion(compatibility.layoutVersion)
                        is VaultManifestCompatibility.UnsupportedNewer ->
                            VaultManifestReadResult.UnsupportedNewerVersion(compatibility.layoutVersion)
                    }
                },
                onFailure = { error ->
                    VaultManifestReadResult.Malformed(error.message.orEmpty())
                },
            )
    }

    private fun Any.compatibility(): VaultManifestCompatibility {
        val app = when (this) {
            is VaultRootManifest -> app
            is VaultMangaManifest -> app
            else -> return VaultManifestCompatibility.NotVault
        }
        if (app != CONTENT_VAULT_APP_ID) return VaultManifestCompatibility.NotVault

        val layoutVersion = when (this) {
            is VaultRootManifest -> layoutVersion
            is VaultMangaManifest -> layoutVersion
            else -> error("Unexpected manifest type")
        }

        return when {
            layoutVersion in MIN_SUPPORTED_VAULT_LAYOUT_VERSION..CURRENT_VAULT_LAYOUT_VERSION ->
                VaultManifestCompatibility.Current
            layoutVersion < MIN_SUPPORTED_VAULT_LAYOUT_VERSION ->
                VaultManifestCompatibility.UnsupportedOlder(layoutVersion)
            else -> VaultManifestCompatibility.UnsupportedNewer(layoutVersion)
        }
    }
}

@Serializable
private data class LegacyVaultRootManifest(
    val app: String = CONTENT_VAULT_APP_ID,
    @SerialName("contentVaultIdentity")
    val identity: String,
    val displayName: String,
    val layoutVersion: Long,
    val revisionId: String,
    val revisionNumber: Long,
    val writerId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val summary: VaultCatalogueSummary = VaultCatalogueSummary(),
    val manga: List<LegacyVaultMangaManifestPointer> = emptyList(),
) {
    fun toRootManifest(): VaultRootManifest = VaultRootManifest(
        app = app,
        identity = identity,
        displayName = displayName,
        layoutVersion = layoutVersion,
        revisionId = revisionId,
        revisionNumber = revisionNumber,
        writerId = writerId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        summary = summary,
        manga = manga
            .filter { it.legacyState != LegacyVaultMangaState.REMOVED }
            .map {
                VaultMangaManifestPointer(
                    identity = it.identity,
                    path = it.path,
                    title = it.title,
                    revisionId = it.revisionId,
                    revisionNumber = it.revisionNumber,
                    updatedAt = it.updatedAt,
                )
            },
    )
}

@Serializable
private data class LegacyVaultMangaManifestPointer(
    val identity: String,
    val path: String,
    val title: String,
    @SerialName("collectionState")
    val legacyState: LegacyVaultMangaState = LegacyVaultMangaState.ACTIVE,
    val revisionId: String,
    val revisionNumber: Long,
    val updatedAt: Long,
)

@Serializable
private enum class LegacyVaultMangaState {
    ACTIVE,

    @SerialName("TRASHED")
    REMOVED,
}

sealed interface VaultManifestReadResult<out T> {
    data class Success<T>(val manifest: T) : VaultManifestReadResult<T>
    data class UnsupportedOlderVersion(val layoutVersion: Long) : VaultManifestReadResult<Nothing>
    data class UnsupportedNewerVersion(val layoutVersion: Long) : VaultManifestReadResult<Nothing>
    data object NotVault : VaultManifestReadResult<Nothing>
    data class Malformed(val detail: String) : VaultManifestReadResult<Nothing>
}

private sealed interface VaultManifestCompatibility {
    data object Current : VaultManifestCompatibility
    data object NotVault : VaultManifestCompatibility
    data class UnsupportedOlder(val layoutVersion: Long) : VaultManifestCompatibility
    data class UnsupportedNewer(val layoutVersion: Long) : VaultManifestCompatibility
}
