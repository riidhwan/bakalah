package tachiyomi.data.vault

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.ContentVaultIdentity
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultCover
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultManifestSnapshot
import tachiyomi.domain.vault.model.VaultReadingState
import tachiyomi.domain.vault.repository.VaultRepository

class VaultRepositoryImpl(
    private val database: Database,
) : VaultRepository {

    override fun getVaultsAsFlow(): Flow<List<ContentVault>> {
        return database.vaultQueries
            .getVaults(VaultMapper::mapVault)
            .subscribeToList()
    }

    override suspend fun getVaultByIdentity(identity: ContentVaultIdentity): ContentVault? {
        return database.vaultQueries
            .getVaultByIdentity(identity.value, VaultMapper::mapVault)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertVault(vault: ContentVault): Long {
        database.vaultQueries.upsertVault(
            id = vault.id,
            identity = vault.identity.value,
            displayName = vault.displayName,
            layoutVersion = vault.layoutVersion,
            rootRevisionId = vault.rootRevision.id,
            rootRevisionNumber = vault.rootRevision.number,
            writerId = vault.writerId,
            lastCatalogueRefreshAt = vault.lastCatalogueRefreshAt,
            createdAt = vault.createdAt,
            updatedAt = vault.updatedAt,
        )
        return database.vaultQueries
            .getVaultByIdentity(vault.identity.value)
            .awaitAsOne()
            ._id
    }

    override fun getMangaAsFlow(vaultId: Long): Flow<List<VaultManga>> {
        return database.vaultQueries
            .getMangaForVault(vaultId, VaultMapper::mapManga)
            .subscribeToList()
    }

    override suspend fun getMangaById(id: Long): VaultManga? {
        return database.vaultQueries
            .getMangaById(id, VaultMapper::mapManga)
            .awaitAsOneOrNull()
    }

    override suspend fun getMangaByIdentity(vaultId: Long, identity: VaultIdentity): VaultManga? {
        return database.vaultQueries
            .getMangaByIdentity(vaultId, identity.value, VaultMapper::mapManga)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertManga(manga: VaultManga): Long {
        database.vaultQueries.upsertManga(
            id = manga.id,
            vaultId = manga.vaultId,
            identity = manga.identity.value,
            title = manga.metadata.title,
            sortKey = manga.sortKey,
            author = manga.metadata.author,
            artist = manga.metadata.artist,
            description = manga.metadata.description,
            status = manga.metadata.status,
            coverId = manga.coverId,
            revisionId = manga.revision.id,
            revisionNumber = manga.revision.number,
            createdAt = manga.createdAt,
            updatedAt = manga.updatedAt,
        )
        return database.vaultQueries
            .getMangaByIdentity(manga.vaultId, manga.identity.value)
            .awaitAsOne()
            ._id
    }

    override fun getChaptersAsFlow(mangaId: Long): Flow<List<VaultChapter>> {
        return database.vaultQueries
            .getChaptersForManga(mangaId, VaultMapper::mapChapter)
            .subscribeToList()
    }

    override suspend fun upsertChapters(mangaId: Long, chapters: List<VaultChapter>) {
        database.transaction {
            if (chapters.isEmpty()) {
                database.vaultQueries.deleteChaptersForManga(mangaId)
            } else {
                database.vaultQueries.deleteChaptersNotInIdentities(mangaId, chapters.map { it.identity.value })
                chapters.forEach { chapter ->
                    database.vaultQueries.upsertChapter(
                        id = chapter.id,
                        mangaId = mangaId,
                        identity = chapter.identity.value,
                        title = chapter.title,
                        chapterNumber = chapter.chapterNumber,
                        volumeNumber = chapter.volumeNumber,
                        scanlator = chapter.scanlator,
                        sourceOrder = chapter.sourceOrder,
                        contentPath = chapter.content.path,
                        contentFormat = chapter.content.format,
                        sizeBytes = chapter.content.sizeBytes,
                        checksumSha256 = chapter.content.checksumSha256,
                        revisionId = chapter.revision.id,
                        revisionNumber = chapter.revision.number,
                        dateUpload = chapter.dateUpload,
                        createdAt = chapter.createdAt,
                        updatedAt = chapter.updatedAt,
                    )
                }
            }
        }
    }

    override suspend fun getLabels(vaultId: Long): List<VaultLabel> {
        return database.vaultQueries
            .getLabels(vaultId, VaultMapper::mapLabel)
            .awaitAsList()
    }

    override suspend fun upsertLabels(vaultId: Long, labels: List<VaultLabel>) {
        database.transaction {
            if (labels.isEmpty()) {
                database.vaultQueries.deleteLabelsForVault(vaultId)
            } else {
                database.vaultQueries.deleteLabelsNotInIdentities(vaultId, labels.map { it.identity.value })
                labels.forEach { label ->
                    database.vaultQueries.upsertLabel(
                        id = label.id,
                        vaultId = vaultId,
                        identity = label.identity.value,
                        name = label.name,
                        sortKey = label.sortKey,
                        createdAt = label.createdAt,
                        updatedAt = label.updatedAt,
                    )
                }
            }
        }
    }

    override suspend fun setMangaLabels(mangaId: Long, labelIds: List<Long>) {
        database.transaction {
            database.vaultQueries.deleteMangaLabels(mangaId)
            labelIds.forEach { labelId ->
                database.vaultQueries.insertMangaLabel(mangaId, labelId)
            }
        }
    }

    override suspend fun upsertCover(cover: VaultCover): Long {
        database.vaultQueries.upsertCover(
            id = cover.id,
            mangaId = cover.mangaId,
            identity = cover.identity.value,
            path = cover.path,
            mediaType = cover.mediaType,
            sizeBytes = cover.sizeBytes,
            checksumSha256 = cover.checksumSha256,
            revisionId = cover.revision.id,
            revisionNumber = cover.revision.number,
            updatedAt = cover.updatedAt,
        )
        return database.vaultQueries
            .getCoverByIdentity(cover.mangaId, cover.identity.value)
            .awaitAsOne()
            ._id
    }

    override suspend fun upsertReadingState(state: VaultReadingState) {
        database.vaultQueries.upsertReadingState(
            chapterId = state.chapterId,
            read = state.read,
            bookmark = state.bookmark,
            lastPageRead = state.lastPageRead,
            lastReadAt = state.lastReadAt,
            updatedAt = state.updatedAt,
        )
    }

    override suspend fun getReadingState(chapterId: Long): VaultReadingState? {
        return database.vaultQueries
            .getReadingState(chapterId, VaultMapper::mapReadingState)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertCacheState(state: VaultChapterCacheState) {
        database.vaultQueries.upsertCacheState(
            chapterId = state.chapterId,
            state = state.state,
            localPath = state.localPath,
            sizeBytes = state.sizeBytes,
            checksumSha256 = state.checksumSha256,
            lastVerifiedAt = state.lastVerifiedAt,
            lastOpenedAt = state.lastOpenedAt,
            updatedAt = state.updatedAt,
            failureReason = state.failureReason,
        )
    }

    override suspend fun getCacheState(chapterId: Long): VaultChapterCacheState? {
        return database.vaultQueries
            .getCacheState(chapterId, VaultMapper::mapCacheState)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertImportTargetHint(hint: ImportTargetHint) {
        database.vaultQueries.upsertImportTargetHint(
            localMangaId = hint.localMangaId,
            localMangaIdentity = hint.localMangaIdentity,
            vaultMangaId = hint.vaultMangaId,
            updatedAt = hint.updatedAt,
        )
    }

    override suspend fun getImportTargetHint(localMangaId: Long): ImportTargetHint? {
        return database.vaultQueries
            .getImportTargetHint(localMangaId, VaultMapper::mapImportTargetHint)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertManifestSnapshot(snapshot: VaultManifestSnapshot): Long {
        database.vaultQueries.upsertManifestSnapshot(
            id = snapshot.id,
            vaultId = snapshot.vaultId,
            mangaId = snapshot.mangaId,
            manifestPath = snapshot.manifestPath,
            revisionId = snapshot.revision.id,
            revisionNumber = snapshot.revision.number,
            body = snapshot.body,
            fetchedAt = snapshot.fetchedAt,
        )
        return database.vaultQueries
            .getManifestSnapshot(snapshot.vaultId, snapshot.manifestPath, snapshot.revision.id)
            .awaitAsOne()
            ._id
    }
}
