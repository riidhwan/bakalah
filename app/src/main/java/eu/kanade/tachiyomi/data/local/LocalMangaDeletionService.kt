package eu.kanade.tachiyomi.data.local

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.vault.model.VaultImportRequestWorkflow
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.isLocal

class LocalMangaDeletionService(
    private val localSourceFileSystem: LocalSourceFileSystem,
    private val mangaRepository: MangaRepository,
    private val vaultRepository: VaultRepository,
    private val coverCache: CoverCache,
    private val activeReaderSessions: ActiveLocalReaderSessions,
    private val sourceManager: SourceManager,
    private val localSourceChangeNotifier: LocalSourceChangeNotifier,
) {

    suspend fun delete(manga: Manga): LocalMangaDeletionResult = withIOContext {
        if (!manga.isLocal()) return@withIOContext LocalMangaDeletionResult.NotLocalManga
        if (activeReaderSessions.isActive(manga.id)) {
            return@withIOContext LocalMangaDeletionResult.BlockedByActiveReader
        }
        if (vaultRepository.hasNonTerminalImportRequest(manga.id, VaultImportRequestWorkflow.LOCAL_IMPORT)) {
            return@withIOContext LocalMangaDeletionResult.BlockedByActiveImport
        }

        val directory = localSourceFileSystem.getMangaDirectory(manga.url)
        if (directory == null) {
            val baseDirectory = localSourceFileSystem.getBaseDirectory()
                ?: return@withIOContext LocalMangaDeletionResult.MangaDirectoryNotFound
            if (baseDirectory.findFile(manga.url) != null) {
                return@withIOContext LocalMangaDeletionResult.MangaDirectoryNotFound
            }
            return@withIOContext cleanupAppState(manga)
        }
        if (!directory.isDirectory || directory.name != manga.url) {
            return@withIOContext LocalMangaDeletionResult.MangaDirectoryNotFound
        }

        runCatching { directory.deleteRecursively() }
            .getOrElse { return@withIOContext LocalMangaDeletionResult.FileDeletionFailed }
        if (directory.exists()) {
            return@withIOContext LocalMangaDeletionResult.FileDeletionFailed
        }

        cleanupAppState(manga)
    }

    private suspend fun cleanupAppState(manga: Manga): LocalMangaDeletionResult {
        return runCatching {
            coverCache.deleteFromCache(manga, deleteCustomCover = true)
            vaultRepository.deleteImportTargetHint(manga.id)
            if (mangaRepository.deleteById(manga.id)) {
                (sourceManager.get(LocalSource.ID) as? LocalSource)?.invalidateSearchCache()
                localSourceChangeNotifier.notifyChanged()
                LocalMangaDeletionResult.Deleted
            } else {
                LocalMangaDeletionResult.StateCleanupFailed
            }
        }.getOrElse {
            LocalMangaDeletionResult.StateCleanupFailed
        }
    }

    private fun UniFile.deleteRecursively() {
        if (isDirectory) {
            listFiles().orEmpty().forEach { it.deleteRecursively() }
        }
        delete()
    }
}

sealed interface LocalMangaDeletionResult {
    data object Deleted : LocalMangaDeletionResult
    data object NotLocalManga : LocalMangaDeletionResult
    data object MangaDirectoryNotFound : LocalMangaDeletionResult
    data object BlockedByActiveReader : LocalMangaDeletionResult
    data object BlockedByActiveImport : LocalMangaDeletionResult
    data object FileDeletionFailed : LocalMangaDeletionResult
    data object StateCleanupFailed : LocalMangaDeletionResult
}
