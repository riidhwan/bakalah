package tachiyomi.domain.vault.interactor

import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.VaultCatalogueMangaRefresh
import tachiyomi.domain.vault.model.VaultCatalogueRefresh
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterThumbnail
import tachiyomi.domain.vault.model.VaultCover
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaManifest
import tachiyomi.domain.vault.model.VaultManifestCodec
import tachiyomi.domain.vault.model.VaultManifestReadResult
import tachiyomi.domain.vault.model.VaultManifestSnapshot
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision
import tachiyomi.domain.vault.model.VaultRootManifest

class BuildVaultCatalogueRefresh(
    private val codec: VaultManifestCodec,
) {
    fun build(
        rootManifestPath: String,
        rootManifestBody: String,
        mangaManifestBodies: Map<String, String>,
        existingVault: ContentVault?,
        fetchedAt: Long,
    ): VaultCatalogueRefreshBuildResult {
        val root = when (val result = codec.decodeRoot(rootManifestBody)) {
            is VaultManifestReadResult.Success -> result.manifest
            is VaultManifestReadResult.UnsupportedOlderVersion ->
                return VaultCatalogueRefreshBuildResult.UnsupportedOlderVersion(result.layoutVersion)
            is VaultManifestReadResult.UnsupportedNewerVersion ->
                return VaultCatalogueRefreshBuildResult.UnsupportedNewerVersion(result.layoutVersion)
            VaultManifestReadResult.NotVault -> return VaultCatalogueRefreshBuildResult.NotVault
            is VaultManifestReadResult.Malformed -> return VaultCatalogueRefreshBuildResult.Malformed(rootManifestPath)
        }

        val decodedManga = root.manga.map { pointer ->
            val body =
                mangaManifestBodies[pointer.path] ?: return VaultCatalogueRefreshBuildResult.MissingMangaManifest(
                    pointer.path,
                )
            val manifest = when (val result = codec.decodeManga(body)) {
                is VaultManifestReadResult.Success -> result.manifest
                is VaultManifestReadResult.UnsupportedOlderVersion ->
                    return VaultCatalogueRefreshBuildResult.UnsupportedOlderVersion(result.layoutVersion)
                is VaultManifestReadResult.UnsupportedNewerVersion ->
                    return VaultCatalogueRefreshBuildResult.UnsupportedNewerVersion(result.layoutVersion)
                VaultManifestReadResult.NotVault -> return VaultCatalogueRefreshBuildResult.NotVault
                is VaultManifestReadResult.Malformed -> return VaultCatalogueRefreshBuildResult.Malformed(pointer.path)
            }
            if (manifest.vaultIdentity != root.identity || manifest.mangaIdentity != pointer.identity) {
                return VaultCatalogueRefreshBuildResult.IdentityMismatch(pointer.path)
            }
            pointer to manifest
        }

        val identity = ContentVaultIdentity(root.identity)
        val vault = ContentVault(
            id = existingVault?.id ?: -1,
            identity = identity,
            displayName = root.displayName,
            layoutVersion = root.layoutVersion,
            rootRevision = VaultRevision(root.revisionId, root.revisionNumber),
            writerId = root.writerId,
            lastCatalogueRefreshAt = fetchedAt,
            createdAt = existingVault?.createdAt ?: root.createdAt,
            updatedAt = fetchedAt,
        )

        val labels = decodedManga
            .flatMap { (_, manifest) -> manifest.labels }
            .distinctBy { it.identity }
            .map { label ->
                VaultLabel(
                    id = -1,
                    vaultId = vault.id,
                    identity = VaultIdentity(label.identity),
                    name = label.name,
                    sortKey = label.sortKey,
                    isSensitive = label.isSensitive,
                    createdAt = label.createdAt,
                    updatedAt = label.updatedAt,
                )
            }

        val manga = decodedManga.map { (pointer, manifest) ->
            manifest.toRefresh(pointer, vault.id)
        }

        val snapshots = buildList {
            add(
                VaultManifestSnapshot(
                    id = -1,
                    vaultId = vault.id,
                    mangaId = null,
                    manifestPath = rootManifestPath,
                    revision = VaultRevision(root.revisionId, root.revisionNumber),
                    body = rootManifestBody,
                    fetchedAt = fetchedAt,
                ),
            )
            decodedManga.forEach { (pointer, manifest) ->
                add(
                    VaultManifestSnapshot(
                        id = -1,
                        vaultId = vault.id,
                        mangaId = null,
                        manifestPath = pointer.path,
                        revision = VaultRevision(manifest.revisionId, manifest.revisionNumber),
                        body = mangaManifestBodies.getValue(pointer.path),
                        fetchedAt = fetchedAt,
                    ),
                )
            }
        }

        return VaultCatalogueRefreshBuildResult.Success(
            VaultCatalogueRefresh(
                vault = vault,
                labels = labels,
                manga = manga,
                snapshots = snapshots,
            ),
        )
    }

    private fun VaultMangaManifest.toRefresh(
        pointer: tachiyomi.domain.vault.model.VaultMangaManifestPointer,
        vaultId: Long,
    ): VaultCatalogueMangaRefresh {
        val revision = VaultRevision(revisionId, revisionNumber)
        val metadata = VaultMetadata(
            title = metadata.title,
            author = metadata.author,
            artist = metadata.artist,
            description = metadata.description,
            status = metadata.status,
        )
        return VaultCatalogueMangaRefresh(
            manga = VaultManga(
                id = -1,
                vaultId = vaultId,
                identity = VaultIdentity(mangaIdentity),
                metadata = metadata,
                sortKey = metadata.normalizedTitle,
                coverId = null,
                revision = revision,
                createdAt = createdAt,
                updatedAt = updatedAt,
            ),
            manifestPath = pointer.path,
            labelIdentities = labels.map { VaultIdentity(it.identity) },
            cover = cover?.let {
                VaultCover(
                    id = -1,
                    mangaId = -1,
                    identity = VaultIdentity(it.identity),
                    path = it.path,
                    mediaType = it.mediaType,
                    sizeBytes = it.integrity?.sizeBytes,
                    checksumSha256 = it.integrity?.checksumSha256,
                    revision = VaultRevision(it.revisionId, it.revisionNumber),
                    updatedAt = it.updatedAt,
                )
            },
            chapters = chapters.map { chapter ->
                VaultChapter(
                    id = -1,
                    mangaId = -1,
                    identity = VaultIdentity(chapter.identity),
                    title = chapter.title,
                    chapterNumber = chapter.chapterNumber,
                    volumeNumber = chapter.volumeNumber,
                    scanlator = chapter.scanlator,
                    sourceOrder = chapter.sourceOrder,
                    content = VaultChapterContent(
                        path = chapter.content.path,
                        format = chapter.content.format,
                        sizeBytes = chapter.content.integrity.sizeBytes,
                        checksumSha256 = chapter.content.integrity.checksumSha256,
                    ),
                    revision = VaultRevision(chapter.revisionId, chapter.revisionNumber),
                    dateUpload = chapter.dateUpload,
                    createdAt = chapter.createdAt,
                    updatedAt = chapter.updatedAt,
                    thumbnail = chapter.thumbnail?.let {
                        VaultChapterThumbnail(
                            id = -1,
                            chapterId = -1,
                            identity = VaultIdentity(it.identity),
                            path = it.path,
                            mediaType = it.mediaType,
                            sizeBytes = it.integrity?.sizeBytes,
                            checksumSha256 = it.integrity?.checksumSha256,
                            revision = VaultRevision(it.revisionId, it.revisionNumber),
                            updatedAt = it.updatedAt,
                        )
                    },
                )
            },
        )
    }
}

sealed interface VaultCatalogueRefreshBuildResult {
    data class Success(val refresh: VaultCatalogueRefresh) : VaultCatalogueRefreshBuildResult
    data object NotVault : VaultCatalogueRefreshBuildResult
    data class UnsupportedOlderVersion(val layoutVersion: Long) : VaultCatalogueRefreshBuildResult
    data class UnsupportedNewerVersion(val layoutVersion: Long) : VaultCatalogueRefreshBuildResult
    data class MissingMangaManifest(val manifestPath: String) : VaultCatalogueRefreshBuildResult
    data class IdentityMismatch(val manifestPath: String) : VaultCatalogueRefreshBuildResult
    data class Malformed(val manifestPath: String) : VaultCatalogueRefreshBuildResult
}
