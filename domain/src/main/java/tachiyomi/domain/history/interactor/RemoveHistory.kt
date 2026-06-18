package tachiyomi.domain.history.interactor

import tachiyomi.domain.history.model.HistorySourceFilter
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

class RemoveHistory(
    private val repository: HistoryRepository,
) {

    suspend fun awaitAll(sourceFilter: HistorySourceFilter): Boolean {
        return repository.deleteAllHistory(sourceFilter)
    }

    suspend fun await(history: HistoryWithRelations) {
        repository.resetHistory(history.id)
    }

    suspend fun await(mangaId: Long) {
        repository.resetHistoryByMangaId(mangaId)
    }
}
