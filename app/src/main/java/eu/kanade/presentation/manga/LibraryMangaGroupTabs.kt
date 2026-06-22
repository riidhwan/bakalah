package eu.kanade.presentation.manga

import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.tachiyomi.ui.manga.LibraryMangaGroupTab

@Composable
fun LibraryMangaGroupTabs(
    tabs: List<LibraryMangaGroupTab>,
    onSourceTabClicked: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = tabs.indexOfFirst { it.selected }.coerceAtLeast(0)
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab.selected,
                onClick = { onSourceTabClicked(tab.mangaId) },
                text = {
                    Text(
                        text = tab.sourceName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
