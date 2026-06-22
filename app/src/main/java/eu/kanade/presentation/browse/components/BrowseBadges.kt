package eu.kanade.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun InLibraryBadge(enabled: Boolean) {
    if (enabled) {
        Badge(
            imageVector = Icons.Outlined.CollectionsBookmark,
        )
    }
}

@Composable
internal fun BrowseLibraryBadge(
    inLibrary: Boolean,
    sameTitleLibraryMatch: Boolean,
) {
    when {
        inLibrary -> InLibraryBadge(enabled = true)
        sameTitleLibraryMatch -> Badge(text = stringResource(MR.strings.title_in_library))
    }
}
