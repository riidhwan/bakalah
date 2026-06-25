package eu.kanade.tachiyomi.ui.manga.model

import androidx.compose.runtime.Immutable

@Immutable
data class MangaTrackingUiState(
    val count: Int = 0,
    val hasLoggedInTrackers: Boolean = false,
)
