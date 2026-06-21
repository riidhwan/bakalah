package eu.kanade.tachiyomi.data.vault.publishing

import eu.kanade.tachiyomi.data.vault.remote.VaultRemoteStorageFactory
import eu.kanade.tachiyomi.data.vault.remote.childPath
import eu.kanade.tachiyomi.data.vault.remote.getTextOrNull
import eu.kanade.tachiyomi.data.vault.remote.isSuccess
import eu.kanade.tachiyomi.data.vault.remote.webdav.WebDavVaultRemoteStorageFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import tachiyomi.domain.vault.model.CURRENT_VAULT_LAYOUT_VERSION
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ROOT_VAULT_MANIFEST_NAME
import tachiyomi.domain.vault.model.VaultCatalogueSummary
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestLabel
import tachiyomi.domain.vault.model.VaultManifestMetadata
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import java.util.UUID

class VaultMetadataPublishService internal constructor(
    json: Json,
    private val repository: VaultRepository,
    private val preferences: ContentVaultPreferences,
    private val remoteStorageFactory: VaultRemoteStorageFactory,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        networkHelper: NetworkHelper,
        json: Json,
        repository: VaultRepository,
        preferences: ContentVaultPreferences,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        json = json,
        repository = repository,
        preferences = preferences,
        remoteStorageFactory = WebDavVaultRemoteStorageFactory(networkHelper),
        now = now,
    )

    private val codec = VaultManifestCodec(json)

    suspend fun publish(request: VaultMetadataPublishRequest): VaultMetadataPublishResult {
        val title = request.title.trim()
        if (title.isBlank()) return VaultMetadataPublishResult.TitleRequired

        val manga = repository.getMangaById(request.mangaId) ?: return VaultMetadataPublishResult.MangaNotFound
        val config = preferences.getWebDavConfig()
        if (!config.isComplete) return VaultMetadataPublishResult.IncompleteConfiguration

        val rootPath = config.rootPath.childPath(ROOT_VAULT_MANIFEST_NAME)
        val rootBody = get(config, rootPath) ?: return VaultMetadataPublishResult.ManifestNotFound(rootPath)
        val root = when (val result = codec.decodeRoot(rootBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultMetadataPublishResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultMetadataPublishResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultMetadataPublishResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultMetadataPublishResult.Malformed(rootPath)
        }

        val expectedIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
        if (expectedIdentity != null && expectedIdentity != root.identity) {
            return VaultMetadataPublishResult.IdentityChanged(ContentVaultIdentity(root.identity))
        }
        val localVault = repository.getVaultByIdentity(ContentVaultIdentity(root.identity))
            ?: return VaultMetadataPublishResult.VaultNotFound
        val remoteRevision = VaultRevision(root.revisionId, root.revisionNumber)
        if (localVault.rootRevision != remoteRevision) {
            return VaultMetadataPublishResult.RevisionMismatch(remoteRevision)
        }

        val pointer = root.manga.firstOrNull { it.identity == manga.identity.value }
            ?: return VaultMetadataPublishResult.MangaNotFound
        val remoteManifests = root.manga.associateWith { manifestPointer ->
            val path = config.rootPath.childPath(manifestPointer.path)
            val body = get(config, path) ?: return VaultMetadataPublishResult.ManifestNotFound(manifestPointer.path)
            val manifest = when (val result = codec.decodeManga(body)) {
                is VaultManifestReadResult.Success -> result.manifest
                is VaultManifestReadResult.UnsupportedOlderVersion ->
                    return VaultMetadataPublishResult.UnsupportedOlderVersion(result.layoutVersion)
                is VaultManifestReadResult.UnsupportedNewerVersion ->
                    return VaultMetadataPublishResult.UnsupportedNewerVersion(result.layoutVersion)
                VaultManifestReadResult.NotVault -> return VaultMetadataPublishResult.NotVault
                is VaultManifestReadResult.Malformed -> return VaultMetadataPublishResult.Malformed(
                    manifestPointer.path,
                )
            }
            if (manifest.vaultIdentity != root.identity || manifest.mangaIdentity != manifestPointer.identity) {
                return VaultMetadataPublishResult.IdentityMismatch(manifestPointer.path)
            }
            manifest
        }
        val timestamp = now()
        val metadata = VaultManifestMetadata(
            title = title,
            author = request.author.trimToNull(),
            artist = request.artist.trimToNull(),
            description = request.description.trimToNull(),
            status = request.status,
        )
        val labelPlan = buildLabelPlan(
            edits = request.labels,
            existingLabels = remoteManifests.values.flatMap { it.labels },
            now = timestamp,
        )
            ?: return VaultMetadataPublishResult.LabelNameConflict

        val updatedManifestEntries = remoteManifests.map { (manifestPointer, manifest) ->
            val updatedLabels = if (manifestPointer.identity == pointer.identity) {
                labelPlan.assignedLabels
            } else {
                manifest.labels
                    .map { labelPlan.editedByIdentity[it.identity] ?: it }
                    .sortedBy { it.sortKey }
            }
            val updatedMetadata = if (manifestPointer.identity == pointer.identity) metadata else manifest.metadata
            val changed = manifestPointer.identity == pointer.identity || updatedLabels != manifest.labels
            val updatedManifest = if (changed) {
                manifest.copy(
                    layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
                    revisionId = UUID.randomUUID().toString(),
                    revisionNumber = manifest.revisionNumber + 1,
                    metadata = updatedMetadata,
                    labels = updatedLabels,
                    updatedAt = timestamp,
                )
            } else {
                manifest
            }
            manifestPointer to updatedManifest
        }
        val activeManifests = updatedManifestEntries.map { it.second }
        val updatedPointers = updatedManifestEntries
            .map { (manifestPointer, manifest) ->
                if (manifest.revisionId != manifestPointer.revisionId) {
                    manifestPointer.copy(
                        title = manifest.metadata.title,
                        revisionId = manifest.revisionId,
                        revisionNumber = manifest.revisionNumber,
                        updatedAt = timestamp,
                    )
                } else {
                    manifestPointer
                }
            }
            .sortedBy { VaultMetadata.normalizeTitle(it.title) }
        val updatedRoot = root.copy(
            layoutVersion = CURRENT_VAULT_LAYOUT_VERSION,
            revisionId = UUID.randomUUID().toString(),
            revisionNumber = root.revisionNumber + 1,
            updatedAt = timestamp,
            summary = VaultCatalogueSummary(
                mangaCount = updatedPointers.size.toLong(),
                chapterCount = activeManifests.sumOf { it.chapters.size }.toLong(),
                labelCount = activeManifests.flatMap { it.labels }.distinctBy { it.identity }.size.toLong(),
                updatedAt = timestamp,
            ),
            manga = updatedPointers,
        )

        updatedManifestEntries
            .filter { (manifestPointer, manifest) -> manifest.revisionId != manifestPointer.revisionId }
            .forEach { (manifestPointer, manifest) ->
                if (!putStaged(config, config.rootPath.childPath(manifestPointer.path), codec.encodeManga(manifest))) {
                    return VaultMetadataPublishResult.PublishFailed
                }
            }
        if (!putStaged(config, rootPath, codec.encodeRoot(updatedRoot))) {
            return VaultMetadataPublishResult.PublishFailed
        }

        return VaultMetadataPublishResult.Published
    }

    private fun buildLabelPlan(
        edits: List<VaultLabelPublishEdit>,
        existingLabels: List<VaultManifestLabel>,
        now: Long,
    ): LabelPublishPlan? {
        val normalizedEdits = edits
            .mapNotNull { edit ->
                val name = edit.name.trim()
                name
                    .takeIf(String::isNotBlank)
                    ?.let {
                        edit.copy(
                            identity = edit.identity?.trim()?.takeIf(String::isNotBlank),
                            name = name,
                        )
                    }
            }
        if (normalizedEdits.map { VaultMetadata.normalizeTitle(it.name) }.distinct().size != normalizedEdits.size) {
            return null
        }

        val existingByIdentity = existingLabels
            .distinctBy { it.identity }
            .associateBy { it.identity }
        val usedNewIdentities = mutableSetOf<String>()
        val editedLabels = normalizedEdits.map { edit ->
            val existing = edit.identity?.let(existingByIdentity::get)
            val identity = existing?.identity ?: edit.identity ?: generateIdentity(usedNewIdentities)
            val sortKey = VaultMetadata.normalizeTitle(edit.name)
            existing
                ?.copy(
                    name = edit.name,
                    sortKey = sortKey,
                    isSensitive = edit.isSensitive,
                    updatedAt = now,
                )
                ?: VaultManifestLabel(
                    identity = identity,
                    name = edit.name,
                    sortKey = sortKey,
                    isSensitive = edit.isSensitive,
                    createdAt = now,
                    updatedAt = now,
                )
        }

        return LabelPublishPlan(
            editedByIdentity = editedLabels.associateBy { it.identity },
            assignedLabels = normalizedEdits
                .zip(editedLabels)
                .filter { (edit, _) -> edit.assigned }
                .map { (_, label) -> label }
                .sortedBy { it.sortKey },
        )
    }

    private fun generateIdentity(used: MutableSet<String>): String {
        while (true) {
            val identity = UUID.randomUUID().toString()
            if (used.add(identity)) return identity
        }
    }

    private suspend fun get(config: WebDavVaultConfig, path: String): String? =
        remoteStorageFactory.create(config).getTextOrNull(path)

    private suspend fun putStaged(config: WebDavVaultConfig, path: String, body: String): Boolean {
        val stagedPath = "$path.staged-${UUID.randomUUID()}"
        return runCatching {
            put(config, stagedPath, body)
            move(config, stagedPath, path)
        }.onFailure {
            delete(config, stagedPath)
        }.isSuccess
    }

    private suspend fun put(config: WebDavVaultConfig, path: String, body: String) {
        check(remoteStorageFactory.create(config).putText(path, body).isSuccess()) { "remote upload failed" }
    }

    private suspend fun move(config: WebDavVaultConfig, stagedPath: String, finalPath: String) {
        check(remoteStorageFactory.create(config).move(stagedPath, finalPath).isSuccess()) { "remote promote failed" }
    }

    private suspend fun delete(config: WebDavVaultConfig, path: String) {
        remoteStorageFactory.create(config).delete(path)
    }

    private fun String.trimToNull(): String? = trim().takeIf(String::isNotBlank)
}

data class VaultMetadataPublishRequest(
    val mangaId: Long,
    val title: String,
    val author: String,
    val artist: String,
    val description: String,
    val status: VaultMangaStatus,
    val labels: List<VaultLabelPublishEdit>,
)

data class VaultLabelPublishEdit(
    val identity: String?,
    val name: String,
    val isSensitive: Boolean,
    val assigned: Boolean,
)

private data class LabelPublishPlan(
    val editedByIdentity: Map<String, VaultManifestLabel>,
    val assignedLabels: List<VaultManifestLabel>,
)

sealed interface VaultMetadataPublishResult {
    data object Published : VaultMetadataPublishResult
    data object TitleRequired : VaultMetadataPublishResult
    data object IncompleteConfiguration : VaultMetadataPublishResult
    data object VaultNotFound : VaultMetadataPublishResult
    data object MangaNotFound : VaultMetadataPublishResult
    data object NotVault : VaultMetadataPublishResult
    data class UnsupportedOlderVersion(val layoutVersion: Long) : VaultMetadataPublishResult
    data class UnsupportedNewerVersion(val layoutVersion: Long) : VaultMetadataPublishResult
    data class IdentityChanged(val remoteIdentity: ContentVaultIdentity) : VaultMetadataPublishResult
    data class RevisionMismatch(val currentRevision: VaultRevision) : VaultMetadataPublishResult
    data class ManifestNotFound(val manifestPath: String) : VaultMetadataPublishResult
    data class IdentityMismatch(val manifestPath: String) : VaultMetadataPublishResult
    data class Malformed(val manifestPath: String) : VaultMetadataPublishResult
    data object LabelNameConflict : VaultMetadataPublishResult
    data object PublishFailed : VaultMetadataPublishResult
}
