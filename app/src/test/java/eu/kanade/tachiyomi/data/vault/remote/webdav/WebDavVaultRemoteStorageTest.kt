package eu.kanade.tachiyomi.data.vault.remote.webdav

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class WebDavVaultRemoteStorageTest {

    @Test
    fun `propfind parsing excludes root entry and includes child entries`() {
        val entries = WebDavVaultRemoteStorage.parseEntries(
            body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:">
                    <d:response><d:href>/remote.php/dav/files/user/vault/</d:href></d:response>
                    <d:response><d:href>/remote.php/dav/files/user/vault/content-vault.json</d:href></d:response>
                    <d:response><d:href>/remote.php/dav/files/user/vault/manga/series%201.json</d:href></d:response>
                    <d:response><d:href>/remote.php/dav/files/user/other/file.json</d:href></d:response>
                </d:multistatus>
            """.trimIndent(),
            rootPath = "vault",
        )

        entries.map { it.path } shouldContainExactly listOf(
            "/remote.php/dav/files/user/vault/content-vault.json",
            "/remote.php/dav/files/user/vault/manga/series 1.json",
        )
    }
}
