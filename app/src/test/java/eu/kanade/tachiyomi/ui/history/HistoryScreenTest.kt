package eu.kanade.tachiyomi.ui.history

import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

class HistoryScreenTest {

    @Test
    fun `library history screen is serializable for saved state`() {
        serialize(HistoryScreen.library(excludedLocalSourceId = 0L, title = "Library history")).size shouldBeGreaterThan
            0
    }

    @Test
    fun `local history screen is serializable for saved state`() {
        serialize(HistoryScreen.local(localSourceId = 0L, title = "Local history")).size shouldBeGreaterThan 0
    }

    private fun serialize(screen: HistoryScreen): ByteArray {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(screen) }
        return bytes.toByteArray()
    }
}
