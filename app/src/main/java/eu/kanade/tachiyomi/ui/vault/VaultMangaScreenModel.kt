package eu.kanade.tachiyomi.ui.vault

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterCacheState
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.repository.VaultRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VaultMangaScreenModel(
    private val mangaId: Long,
    private val repository: VaultRepository = Injekt.get(),
) : StateScreenModel<VaultMangaScreenModel.State>(State()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            val manga = repository.getMangaById(mangaId)
            mutableState.update {
                it.copy(
                    manga = manga,
                    isLoading = manga == null,
                )
            }
            if (manga == null) {
                _events.send(Event.LoadFailed)
                return@launchIO
            }

            combine(
                repository.getChaptersAsFlow(mangaId),
                repository.getCacheStatesForMangaAsFlow(mangaId),
            ) { chapters, cacheStates ->
                val cacheByChapter = cacheStates.associateBy { it.chapterId }
                chapters.map { chapter ->
                    VaultChapterItem(
                        chapter = chapter,
                        cacheState = cacheByChapter[chapter.id],
                    )
                }
            }
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(Event.LoadFailed)
                }
                .collectLatest { chapters ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            chapters = chapters,
                            localCacheUsageBytes = chapters.sumOf { item ->
                                item.cacheState
                                    ?.takeIf { state -> state.state == VaultCacheState.CACHED }
                                    ?.sizeBytes
                                    ?: 0L
                            },
                            vaultStorageUsageBytes = chapters.sumOf { item -> item.chapter.content.sizeBytes },
                        )
                    }
                }
        }
    }

    fun reportUnavailable(action: VaultScreenModel.PendingAction) {
        screenModelScope.launchIO {
            _events.send(Event.PendingActionUnavailable(action))
        }
    }

    data class VaultChapterItem(
        val chapter: VaultChapter,
        val cacheState: VaultChapterCacheState?,
    ) {
        val state: VaultCacheState
            get() = cacheState?.state ?: VaultCacheState.VAULT_ONLY
    }

    sealed interface Event {
        data object LoadFailed : Event
        data class PendingActionUnavailable(val action: VaultScreenModel.PendingAction) : Event
    }

    data class State(
        val isLoading: Boolean = true,
        val manga: VaultManga? = null,
        val chapters: List<VaultChapterItem> = emptyList(),
        val localCacheUsageBytes: Long = 0,
        val vaultStorageUsageBytes: Long = 0,
    )
}
