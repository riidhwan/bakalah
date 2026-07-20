package eu.kanade.tachiyomi.extension.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExtensionLoaderTest {

    @Test
    fun `tachiyomix 1_6 extensions are supported`() {
        ExtensionLoader.isLibVersionSupported(TACHIYOMIX_1_6) shouldBe true
    }

    private companion object {
        const val TACHIYOMIX_1_6 = 1.6
    }
}
