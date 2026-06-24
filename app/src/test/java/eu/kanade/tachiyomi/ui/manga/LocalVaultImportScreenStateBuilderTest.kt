package eu.kanade.tachiyomi.ui.manga

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.vault.model.ImportTargetHint
import tachiyomi.domain.vault.model.VaultChapter
import tachiyomi.domain.vault.model.VaultChapterContent
import tachiyomi.domain.vault.model.VaultChapterContentFormat
import tachiyomi.domain.vault.model.VaultIdentity
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMangaStatus
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.domain.vault.model.VaultRevision

class LocalVaultImportScreenStateBuilderTest {

    private val builder = LocalVaultImportScreenStateBuilder()

    @Test
    fun `build links persisted target when source identity matches`() {
        val target = target(id = 4, title = "Target")
        val state = builder.build(
            workflow = LocalVaultWorkflow.LocalImport,
            expectedSourceIdentity = "local-series",
            vaultManga = listOf(target),
            vaultChapters = emptyList(),
            hint = hint(vaultMangaId = target.id, sourceIdentity = "local-series"),
            pendingTargetOverride = null,
            chapters = emptyList(),
            localChapterDuplicateKeys = emptyMap(),
            isImportRunning = true,
        )

        state.targetState shouldBe LocalVaultImportTargetState.Linked(target.id, "Target")
        state.targetMangaIdForDuplicates shouldBe target.id
        state.isImportRunning shouldBe true
    }

    @Test
    fun `build marks mismatched persisted target as stale`() {
        val target = target(id = 4, title = "Target")
        val state = builder.build(
            workflow = LocalVaultWorkflow.LocalImport,
            expectedSourceIdentity = "new-local-series",
            vaultManga = listOf(target),
            vaultChapters = emptyList(),
            hint = hint(vaultMangaId = target.id, sourceIdentity = "old-local-series"),
            pendingTargetOverride = null,
            chapters = emptyList(),
            localChapterDuplicateKeys = emptyMap(),
            isImportRunning = false,
        )

        state.targetState shouldBe LocalVaultImportTargetState.Stale
        state.targetMangaIdForDuplicates shouldBe null
    }

    @Test
    fun `build detects local import duplicates by source file key`() {
        val state = builder.build(
            workflow = LocalVaultWorkflow.LocalImport,
            expectedSourceIdentity = "local-series",
            vaultManga = emptyList(),
            vaultChapters = listOf(
                vaultChapter(mangaId = 9, path = "vault/Manga/Chapter 01.cbz"),
                vaultChapter(mangaId = 8, path = "vault/Other/Chapter 02.cbz"),
            ),
            hint = null,
            pendingTargetOverride = LocalVaultImportTargetSelection.Existing(9),
            chapters = listOf(
                chapter(url = "folder/Chapter 01.cbz", name = "Chapter 1"),
                chapter(url = "folder/Chapter 02.cbz", name = "Chapter 2"),
            ),
            localChapterDuplicateKeys = mapOf(
                "folder/Chapter 01.cbz" to "chapter 01",
                "folder/Chapter 02.cbz" to "chapter 02",
            ),
            isImportRunning = false,
        )

        state.duplicateChapterSelectionIds.shouldContainExactly("folder/Chapter 01.cbz")
    }

    @Test
    fun `build detects library capture duplicates by normalized title`() {
        val state = builder.build(
            workflow = LocalVaultWorkflow.LibraryCapture,
            expectedSourceIdentity = "100:https://example.test/manga",
            vaultManga = emptyList(),
            vaultChapters = listOf(
                vaultChapter(mangaId = 9, title = "Chapter   One"),
            ),
            hint = null,
            pendingTargetOverride = LocalVaultImportTargetSelection.Existing(9),
            chapters = listOf(
                chapter(url = "chapter-one", name = " chapter one "),
                chapter(url = "chapter-two", name = "Chapter Two"),
            ),
            localChapterDuplicateKeys = emptyMap(),
            isImportRunning = false,
        )

        state.duplicateChapterSelectionIds.shouldContainExactly("chapter-one")
    }

    private fun hint(vaultMangaId: Long, sourceIdentity: String): ImportTargetHint {
        return ImportTargetHint(
            localMangaId = 1,
            localMangaIdentity = "local-series",
            sourceIdentity = sourceIdentity,
            vaultMangaId = vaultMangaId,
            updatedAt = 0,
        )
    }

    private fun target(id: Long, title: String): VaultManga {
        return VaultManga(
            id = id,
            vaultId = 1,
            identity = VaultIdentity("manga-$id"),
            metadata = VaultMetadata(
                title = title,
                author = null,
                artist = null,
                description = null,
                status = VaultMangaStatus.UNKNOWN,
            ),
            sortKey = VaultMetadata.normalizeTitle(title),
            coverId = null,
            revision = VaultRevision("rev-$id", 1),
            createdAt = 0,
            updatedAt = 0,
        )
    }

    private fun chapter(url: String, name: String): Chapter {
        return Chapter.create().copy(
            id = url.hashCode().toLong(),
            url = url,
            name = name,
        )
    }

    private fun vaultChapter(
        mangaId: Long,
        title: String = "Chapter",
        path: String = "vault/Manga/Chapter.cbz",
    ): VaultChapter {
        return VaultChapter(
            id = path.hashCode().toLong(),
            mangaId = mangaId,
            identity = VaultIdentity("chapter-${path.hashCode()}"),
            title = title,
            chapterNumber = -1.0,
            volumeNumber = null,
            scanlator = null,
            sourceOrder = 0,
            content = VaultChapterContent(
                path = path,
                format = VaultChapterContentFormat.CBZ,
                sizeBytes = 0,
                checksumSha256 = "",
            ),
            revision = VaultRevision.initial(),
            dateUpload = 0,
            createdAt = 0,
            updatedAt = 0,
        )
    }
}
