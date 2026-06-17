package eu.kanade.tachiyomi.data.vault.publishing

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.vault.staging.toHex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.vault.model.WebDavVaultConfig
import java.io.File
import java.security.MessageDigest

class VaultContentUploaderTest {
    @TempDir
    lateinit var tempDir: File

    private val uploader = VaultContentUploader()
    private val config = WebDavVaultConfig(
        serverUrl = "https://example.invalid",
        username = "user",
        password = "password",
        rootPath = "vault",
    )

    @Test
    fun `chapter file upload writes verified staged file and returns final content path`() = runTest {
        val storage = FakeUploadStorage()
        val chapterFile = tempFile("Chapter 1.cbz", "chapter-body")

        val upload = uploader.uploadChapterFile(
            storage = storage,
            config = config,
            mangaIdentity = "manga-1",
            contentIdentity = "chapter-1",
            chapterFile = chapterFile,
        )

        upload.finalPath shouldBe "content/manga-1/chapter-1/Chapter 1.cbz"
        upload.stagedPath.startsWith(".staging/add-to-vault/chapter-1/") shouldBe true
        upload.stagedPath.endsWith("-Chapter 1.cbz") shouldBe true
        storage.directories shouldContainExactly listOf(
            "vault/content",
            "vault/content/manga-1",
            "vault/content/manga-1/chapter-1",
            "vault/.staging",
            "vault/.staging/add-to-vault",
            "vault/.staging/add-to-vault/chapter-1",
        )
        storage.putFiles shouldContainExactly listOf("vault/${upload.stagedPath}")
        storage.readBytes shouldContainExactly listOf("vault/${upload.stagedPath}")
    }

    @Test
    fun `chapter file upload can use capture-owned remote file name`() = runTest {
        val storage = FakeUploadStorage()
        val chapterFile = tempFile("staged.cbz", "chapter-body")

        val upload = uploader.uploadChapterFile(
            storage = storage,
            config = config,
            mangaIdentity = "manga-1",
            contentIdentity = "content-1",
            chapterFile = chapterFile,
            remoteFileName = "content-1.cbz",
        )

        upload.finalPath shouldBe "content/manga-1/content-1/content-1.cbz"
        upload.stagedPath.endsWith("-content-1.cbz") shouldBe true
        storage.putFiles shouldContainExactly listOf("vault/${upload.stagedPath}")
    }

    @Test
    fun `file cover upload returns manifest cover with file integrity`() = runTest {
        val storage = FakeUploadStorage()
        val coverFile = tempFile("cover.jpg", "cover-body")

        val uploadedCover = uploader.uploadCover(
            storage = storage,
            config = config,
            mangaIdentity = "manga-1",
            cover = VaultUploadCover.File(
                file = coverFile,
                extension = "jpg",
                mediaType = "image/jpeg",
            ),
            now = 200L,
        )

        val cover = uploadedCover.cover
        cover.path.startsWith("content/manga-1/cover/") shouldBe true
        cover.path.endsWith(".jpg") shouldBe true
        uploadedCover.upload.finalPath shouldBe cover.path
        uploadedCover.upload.stagedPath.startsWith(".staging/add-to-vault/${cover.identity}/") shouldBe true
        cover.mediaType shouldBe "image/jpeg"
        val integrity = cover.integrity ?: error("integrity")
        integrity.sizeBytes shouldBe "cover-body".toByteArray().size.toLong()
        integrity.checksumSha256 shouldBe "cover-body".toByteArray().sha256()
        cover.updatedAt shouldBe 200L
        storage.directories shouldContainExactly listOf(
            "vault/content",
            "vault/content/manga-1",
            "vault/content/manga-1/cover",
            "vault/.staging",
            "vault/.staging/add-to-vault",
            "vault/.staging/add-to-vault/${cover.identity}",
        )
        storage.putFiles shouldContainExactly listOf("vault/${uploadedCover.upload.stagedPath}")
        storage.readBytes shouldContainExactly listOf("vault/${uploadedCover.upload.stagedPath}")
    }

    @Test
    fun `bytes cover upload returns manifest cover with bytes integrity`() = runTest {
        val storage = FakeUploadStorage()
        val bytes = "cover-body".toByteArray()

        val uploadedCover = uploader.uploadCover(
            storage = storage,
            config = config,
            mangaIdentity = "manga-1",
            cover = VaultUploadCover.Bytes(
                bytes = bytes,
                extension = "png",
                mediaType = "image/png",
            ),
            now = 200L,
        )

        val cover = uploadedCover.cover
        cover.path.startsWith("content/manga-1/cover/") shouldBe true
        cover.path.endsWith(".png") shouldBe true
        cover.mediaType shouldBe "image/png"
        val integrity = cover.integrity ?: error("integrity")
        integrity.sizeBytes shouldBe bytes.size.toLong()
        integrity.checksumSha256 shouldBe bytes.sha256()
        storage.putBytes shouldContainExactly listOf("vault/${uploadedCover.upload.stagedPath}" to "image/png")
    }

    @Test
    fun `chapter upload failure exposes neutral category`() = runTest {
        val storage = FakeUploadStorage(putFileResult = false)
        val chapterFile = tempFile("Chapter 1.cbz", "chapter-body")

        val error = shouldThrow<VaultContentUploadFailure> {
            uploader.uploadChapterFile(
                storage = storage,
                config = config,
                mangaIdentity = "manga-1",
                contentIdentity = "chapter-1",
                chapterFile = chapterFile,
            )
        }

        error.category shouldBe "chapter_upload"
    }

    @Test
    fun `cover upload failure exposes neutral category`() = runTest {
        val storage = FakeUploadStorage(putBytesResult = false)

        val error = shouldThrow<VaultContentUploadFailure> {
            uploader.uploadCover(
                storage = storage,
                config = config,
                mangaIdentity = "manga-1",
                cover = VaultUploadCover.Bytes(
                    bytes = "cover-body".toByteArray(),
                    extension = "png",
                    mediaType = "image/png",
                ),
                now = 200L,
            )
        }

        error.category shouldBe "cover_upload"
    }

    @Test
    fun `chapter remote verification failure exposes neutral category`() = runTest {
        val storage = FakeUploadStorage(corruptReadBack = true)
        val chapterFile = tempFile("Chapter 1.cbz", "chapter-body")

        val error = shouldThrow<VaultContentUploadFailure> {
            uploader.uploadChapterFile(
                storage = storage,
                config = config,
                mangaIdentity = "manga-1",
                contentIdentity = "chapter-1",
                chapterFile = chapterFile,
            )
        }

        error.category shouldBe "chapter_upload"
        storage.deletes.single().startsWith("vault/.staging/add-to-vault/chapter-1/") shouldBe true
    }

    private fun tempFile(name: String, body: String): UniFile {
        val file = tempDir.resolve(name)
        file.writeText(body)
        return UniFile.fromFile(file) ?: error("test file")
    }

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(this)
        return digest.digest().toHex()
    }

    private class FakeUploadStorage(
        private val putFileResult: Boolean = true,
        private val putBytesResult: Boolean = true,
        private val corruptReadBack: Boolean = false,
    ) : VaultContentUploadStorage {
        val directories = mutableListOf<String>()
        val putFiles = mutableListOf<String>()
        val putBytes = mutableListOf<Pair<String, String?>>()
        val readBytes = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        private val files = mutableMapOf<String, ByteArray>()

        override suspend fun getBytes(path: String): ByteArray? {
            readBytes += path
            return files[path]?.let { bytes ->
                if (corruptReadBack) bytes + 1 else bytes
            }
        }

        override suspend fun putFile(path: String, file: UniFile): Boolean {
            putFiles += path
            file.openInputStream().use { input ->
                files[path] = input.readBytes()
            }
            return putFileResult
        }

        override suspend fun putBytes(path: String, bytes: ByteArray, mediaType: String?): Boolean {
            putBytes += path to mediaType
            files[path] = bytes
            return putBytesResult
        }

        override suspend fun createDirectory(path: String): Boolean {
            directories += path
            return true
        }

        override suspend fun delete(path: String): Boolean {
            deletes += path
            files.remove(path)
            return true
        }

        override suspend fun promote(stagedPath: String, finalPath: String): Boolean = true
    }
}
