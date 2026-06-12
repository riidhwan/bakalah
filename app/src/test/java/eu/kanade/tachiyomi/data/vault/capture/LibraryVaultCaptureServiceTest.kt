package eu.kanade.tachiyomi.data.vault.capture

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultContentIntegrity
import tachiyomi.domain.vault.model.VaultManifestChapter
import tachiyomi.domain.vault.model.VaultManifestChapterContent

class LibraryVaultCaptureServiceTest {

    @Test
    fun `captured chapter order uses latest first natural titles`() {
        val ordered = orderLibraryVaultCaptureChapters(
            chapters = listOf(
                manifestChapter("ep-19", "Ep.19 (ch. 19)"),
                manifestChapter("ep-20", "Ep.20 (ch. 20)"),
                manifestChapter("ep-8", "Ep.8 (ch. 8)"),
                manifestChapter("ep-9", "Ep.9 (ch. 9)"),
            ),
            replacementIdentities = emptySet(),
        )

        ordered.map { it.title } shouldBe listOf(
            "Ep.20 (ch. 20)",
            "Ep.19 (ch. 19)",
            "Ep.9 (ch. 9)",
            "Ep.8 (ch. 8)",
        )
        ordered.map { it.sourceOrder } shouldBe listOf(0L, 1L, 2L, 3L)
    }

    private fun manifestChapter(
        identity: String,
        title: String,
        sourceOrder: Long = 0,
    ) = VaultManifestChapter(
        identity = identity,
        title = title,
        chapterNumber = -1.0,
        volumeNumber = null,
        scanlator = null,
        sourceOrder = sourceOrder,
        content = VaultManifestChapterContent(
            path = "content/manga/$identity/$title.cbz",
            format = VaultChapterContentFormat.CBZ,
            integrity = VaultContentIntegrity(
                sizeBytes = 1,
                checksumSha256 = identity,
            ),
        ),
        revisionId = "revision-$identity",
        revisionNumber = 1,
        dateUpload = 1,
        createdAt = 1,
        updatedAt = 1,
    )
}
