package eu.kanade.tachiyomi.ui.manga.source

import eu.kanade.tachiyomi.data.diagnostic.PersistenceDiagnosticRecorder
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.manga.library.LibraryMangaGroupTab
import eu.kanade.tachiyomi.ui.manga.library.MangaLibraryGroupCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

internal class MangaLoadCoordinator(
    private val dependencies: Dependencies,
) {
    private val refreshedOnLoadMangaIds = mutableSetOf<Long>()
    private var loadedMangaId: Long? = null

    fun observe(activeMangaId: Flow<Long>): Flow<MangaLoadSnapshot> {
        return activeMangaId
            .flatMapLatest { selectedMangaId ->
                combine(
                    dependencies.getMangaAndChapters.subscribe(
                        selectedMangaId,
                        applyScanlatorFilter = true,
                    )
                        .distinctUntilChanged(),
                    dependencies.downloadCache.changes,
                    dependencies.downloadManager.queueState,
                ) { mangaAndChapters, _, _ -> mangaAndChapters }
            }
            .map { (manga, chapters) ->
                if (!manga.favorite) {
                    dependencies.persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_DEFAULT_FLAGS) {
                        dependencies.setMangaDefaultChapterFlags.await(manga)
                    }
                }

                val isMangaSwitch = loadedMangaId != manga.id
                loadedMangaId = manga.id

                MangaLoadSnapshot(
                    manga = manga,
                    chapters = chapters,
                    source = dependencies.sourceManager.getOrStub(manga.source),
                    isMangaSwitch = isMangaSwitch,
                    needRefreshInfo = !manga.initialized,
                    needRefreshChapter = chapters.isEmpty(),
                    libraryMangaGroupTabs = dependencies.libraryGroupCoordinator.tabs(manga.id),
                )
            }
    }

    fun takeRefreshOnLoad(snapshot: MangaLoadSnapshot, isActive: Boolean): Boolean {
        val needsRefresh = snapshot.needRefreshInfo || snapshot.needRefreshChapter
        if (!isActive || !needsRefresh || snapshot.manga.id in refreshedOnLoadMangaIds) {
            return false
        }
        refreshedOnLoadMangaIds.add(snapshot.manga.id)
        return true
    }

    data class Dependencies(
        val getMangaAndChapters: GetMangaWithChapters,
        val downloadCache: DownloadCache,
        val downloadManager: DownloadManager,
        val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
        val sourceManager: SourceManager,
        val libraryGroupCoordinator: MangaLibraryGroupCoordinator,
        val persistenceDiagnostics: PersistenceDiagnosticRecorder,
    )
}

internal data class MangaLoadSnapshot(
    val manga: Manga,
    val chapters: List<Chapter>,
    val source: Source,
    val isMangaSwitch: Boolean,
    val needRefreshInfo: Boolean,
    val needRefreshChapter: Boolean,
    val libraryMangaGroupTabs: List<LibraryMangaGroupTab>,
)
