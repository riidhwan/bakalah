package eu.kanade.tachiyomi.ui.local

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.shouldCloseSearchOnNavigateUp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LocalTabTest {

    @Test
    fun `local source browse screen identity is stable`() {
        val firstScreen = LocalTab.localSourceScreenForTesting()
        val secondScreen = LocalTab.localSourceScreenForTesting()

        (firstScreen === secondScreen) shouldBe true
        firstScreen.key shouldBe secondScreen.key
    }

    @Test
    fun `local source search closes instead of navigating up`() {
        val state = BrowseSourceScreenModel.State(
            listing = BrowseSourceScreenModel.Listing.Search(
                query = "manga",
                filters = FilterList(),
            ),
            toolbarQuery = "manga",
        )

        state.shouldCloseSearchOnNavigateUp(
            showNavigateUp = false,
            canNavigateUp = false,
        ) shouldBe true
    }

    @Test
    fun `source search with a back stack navigates up`() {
        val state = BrowseSourceScreenModel.State(
            listing = BrowseSourceScreenModel.Listing.Search(
                query = "manga",
                filters = FilterList(),
            ),
            toolbarQuery = "manga",
        )

        state.shouldCloseSearchOnNavigateUp(
            showNavigateUp = true,
            canNavigateUp = true,
        ) shouldBe false
    }
}
