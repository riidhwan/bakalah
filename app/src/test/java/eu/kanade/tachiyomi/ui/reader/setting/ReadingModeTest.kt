package eu.kanade.tachiyomi.ui.reader.setting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingModeTest {

    @Test
    fun `normalizes every persisted reader mode flag to long strip`() {
        val oldReaderModes = 0..ReadingMode.MASK

        oldReaderModes.forEach { flag ->
            ReadingMode.normalizeFlag(flag) shouldBe ReadingMode.WEBTOON.flagValue
            ReadingMode.fromPreference(flag) shouldBe ReadingMode.WEBTOON
        }
    }

    @Test
    fun `normalizing viewer flags preserves non-reader-mode bits`() {
        val orientationFlag = 0x00000018
        val oldPagedFlag = 0x00000002
        val viewerFlags = orientationFlag or oldPagedFlag

        ReadingMode.normalizeViewerFlags(viewerFlags) shouldBe (orientationFlag or ReadingMode.WEBTOON.flagValue)
    }
}
