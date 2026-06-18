package tachiyomi.domain.history.model

sealed interface HistorySourceFilter {
    data class Library(val excludedLocalSourceId: Long) : HistorySourceFilter
    data class Local(val localSourceId: Long) : HistorySourceFilter
}
