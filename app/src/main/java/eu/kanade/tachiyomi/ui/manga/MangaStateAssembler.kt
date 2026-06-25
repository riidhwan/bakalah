package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.LocalSourceFileSystem

internal class MangaStateAssembler(
    private val libraryPreferences: LibraryPreferences,
    private val localSourceFileSystem: LocalSourceFileSystem,
) {

    fun successState(
        previousState: MangaScreenModel.State,
        manga: Manga,
        source: Source,
        isFromSource: Boolean,
        chapters: List<ChapterList.Item>,
        isMangaSwitch: Boolean,
        isRefreshingData: Boolean,
        libraryMangaGroupTabs: List<LibraryMangaGroupTab>,
    ): MangaScreenModel.State.Success {
        val previousSuccessState = previousState as? MangaScreenModel.State.Success
        return MangaScreenModel.State.Success(
            manga = manga,
            source = source,
            isFromSource = isFromSource,
            chapters = chapters,
            availableScanlators = previousSuccessState
                ?.takeUnless { isMangaSwitch }
                ?.availableScanlators
                ?: emptySet(),
            excludedScanlators = previousSuccessState
                ?.takeUnless { isMangaSwitch }
                ?.excludedScanlators
                ?: emptySet(),
            isRefreshingData = isRefreshingData,
            dialog = previousSuccessState?.dialog,
            hasPromptedToAddBefore = previousSuccessState?.hasPromptedToAddBefore ?: false,
            tracking = if (isMangaSwitch) {
                MangaTrackingUiState()
            } else {
                previousSuccessState?.tracking ?: MangaTrackingUiState()
            },
            hideMissingChapters = libraryPreferences.hideMissingChapters.get(),
            canEditLocalMetadata = source is LocalSource &&
                localSourceFileSystem.getMangaDirectory(manga.url) != null,
            localVaultImport = if (isMangaSwitch) null else previousSuccessState?.localVaultImport,
            libraryMangaGroupTabs = libraryMangaGroupTabs,
        )
    }
}
