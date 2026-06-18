package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

@Composable
internal fun ColumnScope.ReadingModePage(screenModel: ReaderSettingsScreenModel) {
    HeadingItem(MR.strings.pref_category_for_this_series)
    val manga by screenModel.mangaFlow.collectAsState()

    val orientation = remember(manga) { ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt()) }
    SettingsChipRow(MR.strings.rotation_type) {
        ReaderOrientation.entries.map {
            FilterChip(
                selected = it == orientation,
                onClick = { screenModel.onChangeOrientation(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }

    WebtoonViewerSettings(screenModel)
}

@Composable
private fun ColumnScope.WebtoonViewerSettings(screenModel: ReaderSettingsScreenModel) {
    val numberFormat = remember { NumberFormat.getPercentInstance() }

    HeadingItem(MR.strings.webtoon_viewer)

    val webtoonSidePadding by screenModel.preferences.webtoonSidePadding.collectAsState()
    SliderItem(
        value = webtoonSidePadding,
        valueRange = ReaderPreferences.let { it.WEBTOON_PADDING_MIN..it.WEBTOON_PADDING_MAX },
        label = stringResource(MR.strings.pref_webtoon_side_padding),
        valueString = numberFormat.format(webtoonSidePadding / 100f),
        onChange = {
            screenModel.preferences.webtoonSidePadding.set(it)
        },
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_crop_borders),
        pref = screenModel.preferences.cropBordersWebtoon,
    )

    val dualPageSplitWebtoon by screenModel.preferences.dualPageSplitWebtoon.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_dual_page_split),
        pref = screenModel.preferences.dualPageSplitWebtoon,
    )

    if (dualPageSplitWebtoon) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_dual_page_invert),
            pref = screenModel.preferences.dualPageInvertWebtoon,
        )
    }

    val dualPageRotateToFitWebtoon by screenModel.preferences.dualPageRotateToFitWebtoon.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_page_rotate),
        pref = screenModel.preferences.dualPageRotateToFitWebtoon,
    )

    if (dualPageRotateToFitWebtoon) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_page_rotate_invert),
            pref = screenModel.preferences.dualPageRotateToFitInvertWebtoon,
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_double_tap_zoom),
        pref = screenModel.preferences.webtoonDoubleTapZoomEnabled,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
        pref = screenModel.preferences.webtoonDisableZoomOut,
    )
}
