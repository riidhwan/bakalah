package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.diagnostic.PersistenceDiagnosticRecorder
import eu.kanade.tachiyomi.data.local.LocalSourceChangeNotifier
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.manga.library.DuplicateMangaGroupTargetItem
import eu.kanade.tachiyomi.ui.manga.library.canAddMangaToGroup
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetSameTitleLibraryManga
import tachiyomi.domain.manga.interactor.ManageLibraryMangaGroup
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.hasSameArtistLibraryMatch
import tachiyomi.domain.manga.model.hasSameTitleLibraryMatch
import tachiyomi.domain.manga.model.sameArtistLibraryMatchKeys
import tachiyomi.domain.manga.model.sameTitleLibraryMatchKey
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class BrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    private val sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getSameTitleLibraryManga: GetSameTitleLibraryManga = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val manageLibraryMangaGroup: ManageLibraryMangaGroup = Injekt.get(),
    private val localSourceChangeNotifier: LocalSourceChangeNotifier = Injekt.get(),
    private val persistenceDiagnostics: PersistenceDiagnosticRecorder = Injekt.get(),
) : StateScreenModel<BrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)

    init {
        if (source is CatalogueSource) {
            mutableState.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                it.copy(
                    listing = listing,
                    filters = source.getFilterList(),
                    toolbarQuery = query,
                )
            }
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    private val sourceBackedLibraryTitleKeys = getLibraryManga.subscribe()
        .map { libraryManga ->
            libraryManga
                .asSequence()
                .map { it.manga }
                .filter { it.source != LocalSource.ID }
                .map { it.title.sameTitleLibraryMatchKey() }
                .filter { it.isNotBlank() }
                .toSet()
        }
        .stateIn(ioCoroutineScope, SharingStarted.Eagerly, emptySet())
    private val sourceBackedLibraryArtistKeys = getLibraryManga.subscribe()
        .map { libraryManga ->
            libraryManga
                .asSequence()
                .map { it.manga }
                .filter { it.source != LocalSource.ID }
                .flatMap { it.artist.sameArtistLibraryMatchKeys() }
                .toSet()
        }
        .stateIn(ioCoroutineScope, SharingStarted.Eagerly, emptySet())

    val mangaPagerFlowFlow = combine(
        state.map { it.listing }.distinctUntilChanged(),
        if (sourceId == LocalSource.ID) localSourceChangeNotifier.changes else flowOf(0L),
        ::browsePagerKey,
    )
        .distinctUntilChanged()
        .map { key ->
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteManga(sourceId, key.listing.query ?: "", key.listing.filters)
            }.flow.map { pagingData ->
                pagingData.map { manga ->
                    getManga.subscribe(manga.url, manga.source)
                        .map { it ?: manga }
                        .combine(
                            combine(
                                sourceBackedLibraryTitleKeys,
                                sourceBackedLibraryArtistKeys,
                                ::LibraryMatchKeys,
                            ),
                        ) { sourceManga, libraryMatchKeys ->
                            BrowseSourceManga(
                                manga = sourceManga,
                                sameTitleLibraryMatch = sourceManga.hasSameTitleLibraryMatch(
                                    libraryTitleKeys = libraryMatchKeys.titleKeys,
                                    localSourceId = LocalSource.ID,
                                ),
                                sameArtistLibraryMatch = sourceManga.hasSameArtistLibraryMatch(
                                    libraryArtistKeys = libraryMatchKeys.artistKeys,
                                    localSourceId = LocalSource.ID,
                                ),
                            )
                        }
                        .stateIn(ioCoroutineScope)
                }
                    .filter { !hideInLibraryItems || !it.value.manga.favorite }
            }
                .cachedIn(ioCoroutineScope)
        }
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, emptyFlow())

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun resetFilters() {
        if (source !is CatalogueSource) return

        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source !is CatalogueSource) return

        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        if (source !is CatalogueSource) return

        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    fun searchGenre(genreName: String) {
        if (source !is CatalogueSource) return

        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        screenModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                prepareAddedFavorite(manga)
            }

            persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_FAVORITE_WRITE) {
                updateManga.await(new.toMangaUpdate())
            }
        }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    addFavoriteWithCategories(manga, listOf(defaultCategory.id))
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    addFavoriteWithCategories(manga, emptyList())
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga = manga,
                            initialSelection = categories.mapAsCheckboxState { it.id in preselectedIds },
                        ),
                    )
                }
            }
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getSameTitleLibraryManga(manga: Manga): List<MangaWithChapterCount> {
        return getSameTitleLibraryManga.invoke(manga)
    }

    suspend fun getDuplicateMangaGroupTargets(
        duplicates: List<MangaWithChapterCount>,
    ): List<DuplicateMangaGroupTargetItem> {
        val duplicatesByMangaId = duplicates.associateBy { it.manga.id }
        val targets = mutableListOf<DuplicateMangaGroupTargetItem>()
        val addedGroupIds = mutableSetOf<Long>()

        duplicates.forEach { duplicate ->
            val group = manageLibraryMangaGroup.getGroupForManga(duplicate.manga.id)
            if (group == null) {
                targets += DuplicateMangaGroupTargetItem(
                    key = "manga:${duplicate.manga.id}",
                    title = duplicate.manga.title,
                    sourceName = sourceManager.getOrStub(duplicate.manga.source).getNameForMangaInfo(),
                    chapterCount = duplicate.chapterCount,
                    sourceCount = 1,
                    groupId = null,
                    memberMangaIds = listOf(duplicate.manga.id),
                    sourceIds = setOf(duplicate.manga.source),
                )
                return@forEach
            }

            if (!addedGroupIds.add(group.id)) return@forEach

            val primary = group.primary ?: group.members.firstOrNull() ?: return@forEach
            targets += DuplicateMangaGroupTargetItem(
                key = "group:${group.id}",
                title = primary.manga.title,
                sourceName = sourceManager.getOrStub(primary.manga.source).getNameForMangaInfo(),
                chapterCount = group.members.sumOf { member ->
                    duplicatesByMangaId[member.manga.id]?.chapterCount ?: 0L
                },
                sourceCount = group.members.size,
                groupId = group.id,
                memberMangaIds = group.memberMangaIds,
                sourceIds = group.members.map { it.manga.source }.toSet(),
            )
        }

        return targets
    }

    fun addFavoriteToGroup(manga: Manga, targets: List<DuplicateMangaGroupTargetItem>) {
        if (!targets.canAddMangaToGroup(manga.source)) return

        screenModelScope.launch {
            val pendingAddToGroup = PendingAddToGroup(targets)
            if (!addFavoriteWithDefaultCategoryOrPrompt(manga, pendingAddToGroup)) return@launch

            addMangaToSelectedGroup(manga, pendingAddToGroup)
            setDialog(null)
        }
    }

    private suspend fun addFavoriteWithDefaultCategoryOrPrompt(
        manga: Manga,
        pendingAddToGroup: PendingAddToGroup? = null,
    ): Boolean {
        val categories = getCategories()
        val defaultCategoryId = libraryPreferences.defaultCategory.get()
        val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }
        return when {
            defaultCategory != null -> addFavoriteWithCategories(manga, listOf(defaultCategory.id))
            defaultCategoryId == 0 || categories.isEmpty() -> addFavoriteWithCategories(manga, emptyList())
            else -> {
                val preselectedIds = getCategories.await(manga.id).map { it.id }
                setDialog(
                    Dialog.ChangeMangaCategory(
                        manga = manga,
                        initialSelection = categories.mapAsCheckboxState { it.id in preselectedIds },
                        pendingAddToGroup = pendingAddToGroup,
                    ),
                )
                false
            }
        }
    }

    private suspend fun addFavoriteWithCategories(manga: Manga, categoryIds: List<Long>): Boolean {
        return persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_LIBRARY_UPDATE) {
            moveMangaToCategories(manga, categoryIds)
            if (!manga.favorite) {
                val new = manga.copy(
                    favorite = true,
                    dateAdded = Instant.now().toEpochMilli(),
                )
                prepareAddedFavorite(manga)
                persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_FAVORITE_WRITE) {
                    updateManga.await(new.toMangaUpdate())
                }
            }
            true
        }
    }

    private suspend fun prepareAddedFavorite(manga: Manga) {
        persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_DEFAULT_FLAGS) {
            setMangaDefaultChapterFlags.await(manga)
        }
        addTracks.bindEnhancedTrackers(manga, source)
    }

    fun addFavoriteWithCategoriesAndMaybeGroup(manga: Manga, categoryIds: List<Long>) {
        screenModelScope.launch {
            val pendingAddToGroup = (state.value.dialog as? Dialog.ChangeMangaCategory)?.pendingAddToGroup
            if (!addFavoriteWithCategories(manga, categoryIds)) return@launch
            if (pendingAddToGroup != null) {
                addMangaToSelectedGroup(manga, pendingAddToGroup)
            }
            setDialog(null)
        }
    }

    private suspend fun addMangaToSelectedGroup(manga: Manga, pendingAddToGroup: PendingAddToGroup) {
        val targets = pendingAddToGroup.targets
        if (!targets.canAddMangaToGroup(manga.source)) return

        val existingGroupId = targets.mapNotNull { it.groupId }.distinct().singleOrNull()
        val existingMangaIds = targets.flatMap { it.memberMangaIds }.distinct()
        if (existingGroupId == null) {
            val primaryMangaId = existingMangaIds.firstOrNull() ?: return
            manageLibraryMangaGroup.createGroup(
                primaryMangaId = primaryMangaId,
                memberMangaIds = existingMangaIds + manga.id,
            )
        } else {
            manageLibraryMangaGroup.addSources(
                groupId = existingGroupId,
                memberMangaIds = existingMangaIds + manga.id,
            )
        }
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            persistenceDiagnostics.trace(PersistenceDiagnosticRecorder.MANGA_CATEGORIES_WRITE) {
                setMangaCategories.await(
                    mangaId = manga.id,
                    categoryIds = categoryIds.toList(),
                )
            }
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
            val groupTargets: List<DuplicateMangaGroupTargetItem> = emptyList(),
        ) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState.State<Category>>,
            val pendingAddToGroup: PendingAddToGroup? = null,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    data class PendingAddToGroup(
        val targets: List<DuplicateMangaGroupTargetItem>,
    )

    @Immutable
    data class BrowseSourceManga(
        val manga: Manga,
        val sameTitleLibraryMatch: Boolean,
        val sameArtistLibraryMatch: Boolean,
    )

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}

internal data class BrowsePagerKey(
    val listing: BrowseSourceScreenModel.Listing,
    val localSourceVersion: Long,
)

private data class LibraryMatchKeys(
    val titleKeys: Set<String>,
    val artistKeys: Set<String>,
)

internal fun browsePagerKey(
    listing: BrowseSourceScreenModel.Listing,
    localSourceVersion: Long,
): BrowsePagerKey {
    return BrowsePagerKey(
        listing = listing,
        localSourceVersion = localSourceVersion,
    )
}
