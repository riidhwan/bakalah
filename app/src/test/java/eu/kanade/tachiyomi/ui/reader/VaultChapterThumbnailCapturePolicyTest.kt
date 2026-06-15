package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VaultChapterThumbnailCapturePolicyTest {

    @Test
    fun `resolveOwner returns ready page containing crop center`() {
        val page1 = page(index = 0)
        val page2 = page(index = 1)

        val owner = VaultChapterThumbnailCapturePolicy.resolveOwner(
            regions = listOf(
                VaultChapterThumbnailCaptureRegion.Page(page1, rect(top = 0, bottom = 100)),
                VaultChapterThumbnailCaptureRegion.Page(page2, rect(top = 100, bottom = 200)),
            ),
            crop = rect(top = 80, bottom = 140),
        )

        owner shouldBe page2
    }

    @Test
    fun `resolveOwner rejects crop intersecting transition`() {
        val page = page(index = 0)

        val owner = VaultChapterThumbnailCapturePolicy.resolveOwner(
            regions = listOf(
                VaultChapterThumbnailCaptureRegion.Page(page, rect(top = 0, bottom = 100)),
                VaultChapterThumbnailCaptureRegion.Transition(rect(top = 100, bottom = 140)),
            ),
            crop = rect(top = 80, bottom = 120),
        )

        owner shouldBe null
    }

    @Test
    fun `resolveOwner rejects page that is not ready`() {
        val page = page(index = 0, status = Page.State.LoadPage)

        val owner = VaultChapterThumbnailCapturePolicy.resolveOwner(
            regions = listOf(VaultChapterThumbnailCaptureRegion.Page(page, rect(top = 0, bottom = 100))),
            crop = rect(top = 20, bottom = 80),
        )

        owner shouldBe null
    }

    private fun page(
        index: Int,
        status: Page.State = Page.State.Ready,
    ): ReaderPage {
        return ReaderPage(index).apply {
            this.status = status
        }
    }

    private fun rect(
        top: Int,
        bottom: Int,
    ): VaultChapterThumbnailCaptureRect {
        return VaultChapterThumbnailCaptureRect(
            left = 0,
            top = top,
            right = 100,
            bottom = bottom,
        )
    }
}
