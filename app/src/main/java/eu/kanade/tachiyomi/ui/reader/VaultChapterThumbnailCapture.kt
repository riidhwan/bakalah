package eu.kanade.tachiyomi.ui.reader

import android.graphics.Bitmap
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

data class VaultChapterThumbnailCapture(
    val bitmap: Bitmap,
    val regions: List<VaultChapterThumbnailCaptureRegion>,
)

sealed interface VaultChapterThumbnailCaptureRegion {
    val rect: VaultChapterThumbnailCaptureRect

    data class Page(
        val page: ReaderPage,
        override val rect: VaultChapterThumbnailCaptureRect,
    ) : VaultChapterThumbnailCaptureRegion

    data class Transition(
        override val rect: VaultChapterThumbnailCaptureRect,
    ) : VaultChapterThumbnailCaptureRegion
}

data class VaultChapterThumbnailCaptureRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    fun contains(x: Int, y: Int): Boolean {
        return x >= left && x < right && y >= top && y < bottom
    }

    fun intersects(other: VaultChapterThumbnailCaptureRect): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }
}

object VaultChapterThumbnailCapturePolicy {
    fun resolveOwner(
        regions: List<VaultChapterThumbnailCaptureRegion>,
        crop: VaultChapterThumbnailCaptureRect,
    ): ReaderPage? {
        if (regions.any { it is VaultChapterThumbnailCaptureRegion.Transition && it.rect.intersects(crop) }) {
            return null
        }

        val centerX = crop.left + crop.width / 2
        val centerY = crop.top + crop.height / 2
        val region = regions
            .filterIsInstance<VaultChapterThumbnailCaptureRegion.Page>()
            .lastOrNull { it.rect.contains(centerX, centerY) }

        return region?.page?.takeIf { it.status == Page.State.Ready }
    }
}
