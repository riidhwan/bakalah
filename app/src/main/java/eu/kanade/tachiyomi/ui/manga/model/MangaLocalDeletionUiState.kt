package eu.kanade.tachiyomi.ui.manga.model

import androidx.compose.runtime.Immutable

@Immutable
data class MangaLocalDeletionUiState(
    val isDeleting: Boolean = false,
)
