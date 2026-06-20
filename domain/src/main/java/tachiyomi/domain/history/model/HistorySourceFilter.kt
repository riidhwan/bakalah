package tachiyomi.domain.history.model

sealed interface HistorySourceFilter {
    data class Library(val excludedLocalSourceId: Long) : HistorySourceFilter
    data class Local(val localSourceId: Long) : HistorySourceFilter
    data class Source(val excludedLocalSourceId: Long) : HistorySourceFilter
}
