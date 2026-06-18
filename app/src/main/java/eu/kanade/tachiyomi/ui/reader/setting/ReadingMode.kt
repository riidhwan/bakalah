package eu.kanade.tachiyomi.ui.reader.setting

import androidx.annotation.DrawableRes
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import tachiyomi.i18n.MR

enum class ReadingMode(
    val stringRes: StringResource,
    @DrawableRes val iconRes: Int,
    val flagValue: Int,
) {
    WEBTOON(
        MR.strings.webtoon_viewer,
        R.drawable.ic_reader_webtoon_24dp,
        0x00000004,
    ),
    ;

    companion object {
        const val MASK = 0x00000007

        @Suppress("UNUSED_PARAMETER")
        fun fromPreference(preference: Int?): ReadingMode = WEBTOON

        @Suppress("UNUSED_PARAMETER")
        fun normalizeFlag(preference: Int?): Int = WEBTOON.flagValue

        fun normalizeViewerFlags(viewerFlags: Int): Int {
            return viewerFlags and MASK.inv() or normalizeFlag(viewerFlags and MASK)
        }

        @Suppress("UNUSED_PARAMETER")
        fun toViewer(preference: Int?, activity: ReaderActivity): Viewer = WebtoonViewer(activity)
    }
}
