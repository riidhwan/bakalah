package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.local.LocalMangaDeletionService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.manga.chapter.MangaChapterActionCoordinator
import eu.kanade.tachiyomi.ui.manga.chapter.MangaChapterSettingsCoordinator
import eu.kanade.tachiyomi.ui.manga.download.MangaDownloadCoordinator
import eu.kanade.tachiyomi.ui.manga.library.LibraryMangaGroupStateBuilder
import eu.kanade.tachiyomi.ui.manga.library.MangaFetchIntervalCoordinator
import eu.kanade.tachiyomi.ui.manga.library.MangaLibraryActionCoordinator
import eu.kanade.tachiyomi.ui.manga.library.MangaLibraryGroupCoordinator
import eu.kanade.tachiyomi.ui.manga.library.MangaLibraryWorkflowCoordinator
import eu.kanade.tachiyomi.ui.manga.local.MangaLocalDeletionCoordinator
import eu.kanade.tachiyomi.ui.manga.source.MangaLoadCoordinator
import eu.kanade.tachiyomi.ui.manga.source.MangaSessionCoordinator
import eu.kanade.tachiyomi.ui.manga.source.MangaSourceRefreshCoordinator
import eu.kanade.tachiyomi.ui.manga.tracking.MangaTrackingCoordinator
import eu.kanade.tachiyomi.ui.manga.vault.LocalVaultImportScreenStateBuilder
import eu.kanade.tachiyomi.ui.manga.vault.MangaLocalVaultImportCoordinator
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetSameTitleLibraryManga
import tachiyomi.domain.manga.interactor.ManageLibraryMangaGroup
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaScreenModelDependencies {
    internal val libraryPreferences: LibraryPreferences = Injekt.get()
    internal val trackPreferences: TrackPreferences = Injekt.get()
    internal val readerPreferences: ReaderPreferences = Injekt.get()
    internal val getAvailableScanlators: GetAvailableScanlators = Injekt.get()
    internal val getExcludedScanlators: GetExcludedScanlators = Injekt.get()
    internal val setExcludedScanlators: SetExcludedScanlators = Injekt.get()

    private val updateManga: UpdateManga = Injekt.get()
    private val mangaRepository: MangaRepository = Injekt.get()
    private val downloadManager: DownloadManager = Injekt.get()
    private val downloadCache: DownloadCache = Injekt.get()
    private val getMangaAndChapters: GetMangaWithChapters = Injekt.get()
    private val getSameTitleLibraryManga: GetSameTitleLibraryManga = Injekt.get()
    private val setMangaChapterFlags: SetMangaChapterFlags = Injekt.get()
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get()
    private val setReadStatus: SetReadStatus = Injekt.get()
    private val updateChapter: UpdateChapter = Injekt.get()
    private val getCategories: GetCategories = Injekt.get()
    private val getTracks: GetTracks = Injekt.get()
    private val addTracks: AddTracks = Injekt.get()
    private val setMangaCategories: SetMangaCategories = Injekt.get()
    private val manageLibraryMangaGroup: ManageLibraryMangaGroup = Injekt.get()
    private val localSourceFileSystem: LocalSourceFileSystem = Injekt.get()
    private val vaultRepository: VaultRepository = Injekt.get()
    private val contentVaultPreferences: ContentVaultPreferences = Injekt.get()
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get()
    private val filterChaptersForDownload: FilterChaptersForDownload = Injekt.get()
    private val trackerManager: TrackerManager = Injekt.get()
    private val localMangaDeletionService: LocalMangaDeletionService = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val localVaultImportStateBuilder = LocalVaultImportScreenStateBuilder()
    private val libraryMangaGroupStateBuilder = LibraryMangaGroupStateBuilder(
        sourceName = { sourceId -> sourceManager.getOrStub(sourceId).getNameForMangaInfo() },
    )

    internal val mangaStateAssembler = MangaStateAssembler(
        libraryPreferences = libraryPreferences,
        localSourceFileSystem = localSourceFileSystem,
    )
    internal val libraryGroupCoordinator = MangaLibraryGroupCoordinator(
        MangaLibraryGroupCoordinator.Dependencies(
            manageLibraryMangaGroup = manageLibraryMangaGroup,
            libraryMangaGroupStateBuilder = libraryMangaGroupStateBuilder,
        ),
    )
    internal val downloadCoordinator = MangaDownloadCoordinator(downloadManager)
    internal val libraryWorkflowCoordinator = MangaLibraryWorkflowCoordinator(
        libraryActionCoordinator = MangaLibraryActionCoordinator(
            MangaLibraryActionCoordinator.Dependencies(
                libraryPreferences = libraryPreferences,
                getSameTitleLibraryManga = getSameTitleLibraryManga,
                getCategories = getCategories,
                updateManga = updateManga,
                setMangaCategories = setMangaCategories,
                manageLibraryMangaGroup = manageLibraryMangaGroup,
                libraryMangaGroupStateBuilder = libraryMangaGroupStateBuilder,
            ),
        ),
        libraryGroupCoordinator = libraryGroupCoordinator,
        addTracks = addTracks,
    )
    internal val loadCoordinator = MangaLoadCoordinator(
        MangaLoadCoordinator.Dependencies(
            getMangaAndChapters = getMangaAndChapters,
            downloadCache = downloadCache,
            downloadManager = downloadManager,
            setMangaDefaultChapterFlags = setMangaDefaultChapterFlags,
            sourceManager = sourceManager,
            libraryGroupCoordinator = libraryGroupCoordinator,
        ),
    )
    internal val sessionCoordinator = MangaSessionCoordinator(
        MangaSessionCoordinator.Dependencies(
            loadCoordinator = loadCoordinator,
            getAvailableScanlators = getAvailableScanlators,
            getExcludedScanlators = getExcludedScanlators,
        ),
    )
    internal val chapterSettingsCoordinator = MangaChapterSettingsCoordinator(
        MangaChapterSettingsCoordinator.Dependencies(
            libraryPreferences = libraryPreferences,
            setMangaChapterFlags = setMangaChapterFlags,
            setMangaDefaultChapterFlags = setMangaDefaultChapterFlags,
        ),
    )
    internal val fetchIntervalCoordinator = MangaFetchIntervalCoordinator(
        MangaFetchIntervalCoordinator.Dependencies(
            updateManga = updateManga,
            mangaRepository = mangaRepository,
        ),
    )
    internal val sourceRefreshCoordinator = MangaSourceRefreshCoordinator(
        MangaSourceRefreshCoordinator.Dependencies(
            updateManga = updateManga,
            syncChaptersWithSource = syncChaptersWithSource,
            mangaRepository = mangaRepository,
            filterChaptersForDownload = filterChaptersForDownload,
        ),
    )
    internal val trackingCoordinator = MangaTrackingCoordinator(
        MangaTrackingCoordinator.Dependencies(
            getTracks = getTracks,
            trackerManager = trackerManager,
        ),
    )
    internal val localMangaDeletionCoordinator = MangaLocalDeletionCoordinator(
        localMangaDeletionService = localMangaDeletionService,
    )

    internal fun localVaultImportCoordinator(
        runtime: MangaLocalVaultImportCoordinator.Runtime,
        callbacks: MangaLocalVaultImportCoordinator.Callbacks,
    ): MangaLocalVaultImportCoordinator {
        return MangaLocalVaultImportCoordinator(
            runtime = runtime,
            services = MangaLocalVaultImportCoordinator.Services(
                localSourceFileSystem = localSourceFileSystem,
                vaultRepository = vaultRepository,
                contentVaultPreferences = contentVaultPreferences,
                localVaultImportStateBuilder = localVaultImportStateBuilder,
            ),
            callbacks = callbacks,
        )
    }

    internal fun chapterActionCoordinator(
        runtime: MangaChapterActionCoordinator.Runtime,
        callbacks: MangaChapterActionCoordinator.Callbacks,
        skipFiltered: () -> Boolean,
        autoTrackState: () -> AutoTrackState,
    ): MangaChapterActionCoordinator {
        return MangaChapterActionCoordinator(
            runtime = runtime,
            dependencies = MangaChapterActionCoordinator.Dependencies(
                setReadStatus = setReadStatus,
                updateChapter = updateChapter,
                skipFiltered = skipFiltered,
                autoTrackState = autoTrackState,
            ),
            coordinators = MangaChapterActionCoordinator.Coordinators(
                downloadCoordinator = downloadCoordinator,
                trackingCoordinator = trackingCoordinator,
            ),
            callbacks = callbacks,
        )
    }
}
