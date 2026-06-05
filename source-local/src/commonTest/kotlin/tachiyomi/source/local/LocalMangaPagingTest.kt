package tachiyomi.source.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalMangaPagingTest {

    @Test
    fun `first page only includes first page size and has next page`() {
        val page = (1..60).toList().toLocalMangaPage(page = 1)

        assertEquals((1..25).toList(), page.items)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `middle page only includes requested page`() {
        val page = (1..60).toList().toLocalMangaPage(page = 2)

        assertEquals((26..50).toList(), page.items)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `last page includes remaining items and has no next page`() {
        val page = (1..60).toList().toLocalMangaPage(page = 3)

        assertEquals((51..60).toList(), page.items)
        assertFalse(page.hasNextPage)
    }
}
