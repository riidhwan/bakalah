package eu.kanade.presentation.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel

@Composable
fun MangaSourceContent(
    state: MangaScreenModel.State.Success,
    modifier: Modifier = Modifier,
    content: @Composable (MangaScreenModel.State.Success) -> Unit,
) {
    Box(modifier = modifier) {
        content(state)
    }
}
