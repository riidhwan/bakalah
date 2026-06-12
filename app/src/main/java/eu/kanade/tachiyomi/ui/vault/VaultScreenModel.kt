package eu.kanade.tachiyomi.ui.vault

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.vault.publishing.VaultCoverPublishService
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshResult
import eu.kanade.tachiyomi.data.vault.refresh.VaultCatalogueRefreshService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.vault.interactor.GetContentVault
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.repository.VaultRepository
import tachiyomi.domain.vault.service.ContentVaultPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VaultScreenModel(
    private val getContentVault: GetContentVault = Injekt.get(),
    private val repository: VaultRepository = Injekt.get(),
    private val preferences: ContentVaultPreferences = Injekt.get(),
    private val refreshService: VaultCatalogueRefreshService = Injekt.get(),
    private val coverPublishService: VaultCoverPublishService = Injekt.get(),
) : StateScreenModel<VaultScreenModel.State>(State()) {

    private val selectedVaultId = MutableStateFlow<Long?>(null)
    private val requestedCoverIds = mutableSetOf<Long>()

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            getContentVault.subscribeAll()
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(Event.LoadFailed)
                }
                .collectLatest { vaults ->
                    val configuredIdentity = preferences.configuredVaultIdentity.get().takeIf { it.isNotBlank() }
                    val selected = selectedVaultId.value
                        ?.takeIf { id -> vaults.any { it.id == id } }
                        ?: vaults.firstOrNull { it.identity.value == configuredIdentity }?.id
                        ?: vaults.firstOrNull()?.id
                    selectedVaultId.value = selected
                    mutableState.update {
                        it.copy(
                            vaults = vaults,
                            selectedVaultId = selected,
                            isLoading = false,
                        )
                    }
                }
        }

        screenModelScope.launchIO {
            selectedVaultId
                .flatMapLatest { vaultId ->
                    if (vaultId == null) {
                        flowOf(VaultIndex(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()))
                    } else {
                        combine(
                            repository.getMangaAsFlow(vaultId),
                            repository.getChaptersForVaultAsFlow(vaultId),
                            repository.getCacheStatesForVaultAsFlow(vaultId),
                            repository.getLabelsAsFlow(vaultId),
                            repository.getLabelsByMangaForVaultAsFlow(vaultId),
                            ::VaultIndex,
                        )
                    }
                }
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(Event.LoadFailed)
                }
                .collectLatest { index ->
                    mutableState.update { state ->
                        val selectedLabelIdentity = state.selectedLabelIdentity
                            ?.takeIf { selected ->
                                index.labels.any { it.identity.value == selected }
                            }
                        state.copy(
                            mangaItems = index.toMangaItems(),
                            labels = index.labels,
                            selectedLabelIdentity = selectedLabelIdentity,
                            localCacheUsageBytes = index.localCacheUsageBytes,
                            vaultStorageUsageBytes = index.vaultStorageUsageBytes,
                        )
                    }
                }
        }

        screenModelScope.launchIO {
            refreshConfiguredVault(reportSuccess = false)
        }

        screenModelScope.launchIO {
            preferences.includeSensitiveContent.changes()
                .collectLatest { includeSensitive ->
                    mutableState.update { it.copy(includeSensitiveContent = includeSensitive) }
                }
        }
    }

    fun selectVault(vaultId: Long) {
        selectedVaultId.value = vaultId
        mutableState.update { it.copy(selectedVaultId = vaultId) }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setFilter(filter: Filter) {
        mutableState.update { it.copy(filter = filter) }
    }

    fun setSort(sort: Sort) {
        mutableState.update { it.copy(sort = sort) }
    }

    fun setLabelFilter(labelIdentity: String?) {
        mutableState.update { it.copy(selectedLabelIdentity = labelIdentity) }
    }

    fun setIncludeSensitiveContent(include: Boolean) {
        preferences.includeSensitiveContent.set(include)
    }

    fun refreshVault() {
        if (state.value.isRefreshing) return
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            try {
                refreshConfiguredVault(reportSuccess = true)
            } finally {
                mutableState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun loadCover(mangaId: Long) {
        if (!requestedCoverIds.add(mangaId)) return
        screenModelScope.launchIO {
            val coverUri = runCatching {
                coverPublishService.cacheCover(mangaId)
            }.getOrElse {
                logcat(LogPriority.ERROR, it)
                null
            } ?: return@launchIO
            mutableState.update {
                it.copy(coverUris = it.coverUris + (mangaId to coverUri))
            }
        }
    }

    private suspend fun refreshConfiguredVault(reportSuccess: Boolean) {
        runCatching { refreshService.refreshConfiguredVault() }
            .onSuccess { result ->
                when (result) {
                    is VaultCatalogueRefreshResult.Refreshed -> {
                        repository.getVaultByIdentity(result.identity)
                            ?.let { selectedVaultId.value = it.id }
                        if (reportSuccess) {
                            _events.send(
                                Event.RefreshCompleted(
                                    mangaCount = result.mangaCount,
                                    chapterCount = result.chapterCount,
                                ),
                            )
                        }
                    }
                    else -> {
                        val detail = result.toFailureDetail()
                        logcat(LogPriority.ERROR) { "Vault catalogue refresh failed: $detail" }
                        _events.send(Event.RefreshFailed(detail))
                    }
                }
            }
            .onFailure {
                logcat(LogPriority.ERROR, it)
                _events.send(Event.RefreshFailed(it.message ?: it::class.simpleName.orEmpty()))
            }
    }

    private fun VaultCatalogueRefreshResult.toFailureDetail(): String {
        return when (this) {
            VaultCatalogueRefreshResult.IncompleteConfiguration -> "Incomplete configuration"
            VaultCatalogueRefreshResult.NotVault -> "Remote root is not a Bakalah Content Vault"
            is VaultCatalogueRefreshResult.UnsupportedOlderVersion -> "Unsupported older layout version $layoutVersion"
            is VaultCatalogueRefreshResult.UnsupportedNewerVersion -> "Unsupported newer layout version $layoutVersion"
            is VaultCatalogueRefreshResult.IdentityChanged -> "Remote identity changed to ${remoteIdentity.value}"
            is VaultCatalogueRefreshResult.ManifestNotFound -> "Manifest not found: $manifestPath"
            is VaultCatalogueRefreshResult.IdentityMismatch -> "Manifest identity mismatch: $manifestPath"
            is VaultCatalogueRefreshResult.Malformed -> "Malformed manifest: $manifestPath"
            is VaultCatalogueRefreshResult.Refreshed -> "Refreshed $mangaCount manga, $chapterCount chapters"
        }
    }

    fun reportUnavailable(action: PendingAction) {
        screenModelScope.launchIO {
            _events.send(Event.PendingActionUnavailable(action))
        }
    }

    private fun VaultIndex.toMangaItems(): List<VaultMangaItem> {
        val chaptersByManga = chapters.groupBy { it.mangaId }
        val cacheByChapter = cacheStates.associateBy { it.chapterId }
        return manga.map { manga ->
            val chapters = chaptersByManga[manga.id].orEmpty()
            val states = chapters.map { chapter ->
                cacheByChapter[chapter.id]?.state ?: VaultCacheState.VAULT_ONLY
            }
            val labels = labelsByManga[manga.id].orEmpty()
            VaultMangaItem(
                manga = manga,
                labels = labels,
                isSensitive = labels.any { it.isSensitive },
                chapterCount = chapters.size,
                cachedCount = states.count { it == VaultCacheState.CACHED },
                failedCount = states.count { it == VaultCacheState.FAILED || it == VaultCacheState.INTEGRITY_FAULT },
                queuedCount = states.count {
                    it == VaultCacheState.QUEUED ||
                        it == VaultCacheState.CACHING ||
                        it == VaultCacheState.PUBLISHING
                },
                latestChapterUploadAt = chapters.maxOfOrNull { it.dateUpload },
                vaultStorageBytes = chapters.sumOf { it.content.sizeBytes },
                localCacheBytes = chapters.sumOf { chapter ->
                    cacheByChapter[chapter.id]
                        ?.takeIf { it.state == VaultCacheState.CACHED }
                        ?.sizeBytes
                        ?: 0L
                },
            )
        }
    }

    private data class VaultIndex(
        val manga: List<VaultManga>,
        val chapters: List<VaultChapter>,
        val cacheStates: List<VaultChapterCacheState>,
        val labels: List<VaultLabel>,
        val labelsByManga: Map<Long, List<VaultLabel>>,
    ) {
        val localCacheUsageBytes: Long
            get() = cacheStates
                .filter { it.state == VaultCacheState.CACHED }
                .sumOf { it.sizeBytes ?: 0L }

        val vaultStorageUsageBytes: Long
            get() = chapters.sumOf { it.content.sizeBytes }
    }

    data class VaultMangaItem(
        val manga: VaultManga,
        val labels: List<VaultLabel>,
        val isSensitive: Boolean,
        val chapterCount: Int,
        val cachedCount: Int,
        val failedCount: Int,
        val queuedCount: Int,
        val latestChapterUploadAt: Long?,
        val vaultStorageBytes: Long,
        val localCacheBytes: Long,
    )

    enum class Filter {
        ALL,
        CACHED,
        VAULT_ONLY,
        QUEUED,
        FAILED,
    }

    enum class Sort {
        TITLE,
        LATEST_CHAPTER,
        CHAPTER_COUNT,
    }

    enum class PendingAction {
        CACHE,
        EVICT,
        RETRY,
        DELETE,
    }

    sealed interface Event {
        data object LoadFailed : Event
        data class RefreshCompleted(
            val mangaCount: Int,
            val chapterCount: Int,
        ) : Event
        data class RefreshFailed(val detail: String) : Event
        data class PendingActionUnavailable(val action: PendingAction) : Event
    }

    data class State(
        val isLoading: Boolean = true,
        val vaults: List<ContentVault> = emptyList(),
        val selectedVaultId: Long? = null,
        val mangaItems: List<VaultMangaItem> = emptyList(),
        val searchQuery: String? = null,
        val filter: Filter = Filter.ALL,
        val selectedLabelIdentity: String? = null,
        val sort: Sort = Sort.TITLE,
        val labels: List<VaultLabel> = emptyList(),
        val includeSensitiveContent: Boolean = false,
        val isRefreshing: Boolean = false,
        val localCacheUsageBytes: Long = 0,
        val vaultStorageUsageBytes: Long = 0,
        val coverUris: Map<Long, String> = emptyMap(),
    ) {
        val selectedVault: ContentVault?
            get() = vaults.firstOrNull { it.id == selectedVaultId }

        val visibleMangaItems: List<VaultMangaItem>
            get() {
                val query = searchQuery?.trim()?.lowercase().orEmpty()
                val selectedLabel = labels.firstOrNull { it.identity.value == selectedLabelIdentity }
                val selectedSensitiveLabel = selectedLabel?.takeIf { it.isSensitive }
                return mangaItems
                    .asSequence()
                    .filter { item ->
                        when {
                            includeSensitiveContent -> true
                            selectedSensitiveLabel != null ->
                                item.labels.any { it.identity == selectedSensitiveLabel.identity }
                            else -> !item.isSensitive
                        }
                    }
                    .filter { item ->
                        selectedLabel == null || item.labels.any { it.identity == selectedLabel.identity }
                    }
                    .filter { item ->
                        query.isBlank() ||
                            item.manga.metadata.title.lowercase().contains(query) ||
                            item.manga.metadata.author?.lowercase()?.contains(query) == true ||
                            item.manga.metadata.artist?.lowercase()?.contains(query) == true ||
                            item.labels.any { it.name.lowercase().contains(query) }
                    }
                    .filter { item ->
                        when (filter) {
                            Filter.ALL -> true
                            Filter.CACHED -> item.cachedCount > 0
                            Filter.VAULT_ONLY -> item.chapterCount > item.cachedCount
                            Filter.QUEUED -> item.queuedCount > 0
                            Filter.FAILED -> item.failedCount > 0
                        }
                    }
                    .let { items ->
                        when (sort) {
                            Sort.TITLE -> items.sortedBy { it.manga.sortKey }
                            Sort.LATEST_CHAPTER -> items.sortedByDescending { it.latestChapterUploadAt ?: 0L }
                            Sort.CHAPTER_COUNT -> items.sortedByDescending { it.chapterCount }
                        }
                    }
                    .toList()
            }
    }
}
