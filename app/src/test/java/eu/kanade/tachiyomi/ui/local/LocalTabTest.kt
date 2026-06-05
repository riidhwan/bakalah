package eu.kanade.tachiyomi.ui.local

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
}
