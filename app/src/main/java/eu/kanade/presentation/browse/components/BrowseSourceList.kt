package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.BrowseSourceManga
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    mangaList: LazyPagingItems<StateFlow<BrowseSourceManga>>,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        item {
            if (mangaList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val item by mangaList[index]?.collectAsState() ?: return@items
            val manga = item.manga
            BrowseSourceListItem(
                item = item,
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
            )
        }

        item {
            if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceListItem(
    item: BrowseSourceManga,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    val manga = item.manga
    val showLibraryMark = manga.favorite || item.sameTitleLibraryMatch || item.sameArtistLibraryMatch

    MangaListItem(
        title = manga.title,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (showLibraryMark) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        badge = {
            BrowseLibraryBadge(
                inLibrary = manga.favorite,
                sameTitleLibraryMatch = item.sameTitleLibraryMatch,
                sameArtistLibraryMatch = item.sameArtistLibraryMatch,
            )
        },
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
