package tachiyomi.source.local.metadata

import eu.kanade.tachiyomi.source.model.SManga
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalMangaMetadataWriterTest {

    @Test
    fun `creates ComicInfo when missing`() = withTempMangaDir { mangaDir ->
        val writer = writerFor(mangaDir)

        val result = writer.write(defaultEdit())

        assertEquals(LocalMangaMetadataWriteResult.Success, result)
        val comicInfo = mangaDir.comicInfoValues()
        assertEquals("Edited Title", comicInfo["Series"])
        assertEquals("Edited Author", comicInfo["Writer"])
        assertEquals("Edited Artist", comicInfo["Penciller"])
        assertEquals("Edited Description", comicInfo["Summary"])
        assertEquals("Action, Drama", comicInfo["Genre"])
        assertEquals("Ongoing", comicInfo["PublishingStatusTachiyomi"])
    }

    @Test
    fun `updates exposed fields`() = withTempMangaDir { mangaDir ->
        mangaDir.writeComicInfo(
            """
            <ComicInfo>
                <Series>Original Title</Series>
                <Writer>Original Author</Writer>
                <Penciller>Original Artist</Penciller>
                <Summary>Original Description</Summary>
                <Genre>Original Genre</Genre>
                <PublishingStatusTachiyomi>Unknown</PublishingStatusTachiyomi>
            </ComicInfo>
            """.trimIndent(),
        )
        val writer = writerFor(mangaDir)

        val result = writer.write(
            defaultEdit(
                title = "New Title",
                author = "New Author",
                artist = "New Artist",
                description = "New Description",
                genres = listOf("Mystery", "Sci-Fi"),
                status = SManga.COMPLETED,
            ),
        )

        assertEquals(LocalMangaMetadataWriteResult.Success, result)
        val comicInfo = mangaDir.comicInfoValues()
        assertEquals("New Title", comicInfo["Series"])
        assertEquals("New Author", comicInfo["Writer"])
        assertEquals("New Artist", comicInfo["Penciller"])
        assertEquals("New Description", comicInfo["Summary"])
        assertEquals("Mystery, Sci-Fi", comicInfo["Genre"])
        assertEquals("Completed", comicInfo["PublishingStatusTachiyomi"])
    }

    @Test
    fun `preserves supported unedited ComicInfo fields`() = withTempMangaDir { mangaDir ->
        mangaDir.writeComicInfo(
            """
            <ComicInfo>
                <Title>Chapter Title</Title>
                <Series>Original Title</Series>
                <Number>42</Number>
                <Inker>Inker Name</Inker>
                <Colorist>Colorist Name</Colorist>
                <Letterer>Letterer Name</Letterer>
                <CoverArtist>Cover Artist Name</CoverArtist>
                <Translator>Translator Name</Translator>
                <Tags>Tag A, Tag B</Tags>
                <Web>https://example.invalid/manga</Web>
                <Categories>Favorites</Categories>
                <SourceMihon>Local</SourceMihon>
            </ComicInfo>
            """.trimIndent(),
        )
        val writer = writerFor(mangaDir)

        val result = writer.write(defaultEdit())

        assertEquals(LocalMangaMetadataWriteResult.Success, result)
        val comicInfo = mangaDir.comicInfoValues()
        assertEquals("Chapter Title", comicInfo["Title"])
        assertEquals("42", comicInfo["Number"])
        assertEquals("Inker Name", comicInfo["Inker"])
        assertEquals("Colorist Name", comicInfo["Colorist"])
        assertEquals("Letterer Name", comicInfo["Letterer"])
        assertEquals("Cover Artist Name", comicInfo["CoverArtist"])
        assertEquals("Translator Name", comicInfo["Translator"])
        assertEquals("Tag A, Tag B", comicInfo["Tags"])
        assertEquals("https://example.invalid/manga", comicInfo["Web"])
        assertEquals("Favorites", comicInfo["Categories"])
        assertEquals("Local", comicInfo["SourceMihon"])
    }

    @Test
    fun `removes noxml after successful metadata write`() = withTempMangaDir { mangaDir ->
        mangaDir.resolve(".noxml").writeText("")
        val writer = writerFor(mangaDir)

        val result = writer.write(defaultEdit())

        assertEquals(LocalMangaMetadataWriteResult.Success, result)
        assertFalse(mangaDir.resolve(".noxml").exists())
    }

    @Test
    fun `rejects blank title`() = withTempMangaDir { mangaDir ->
        val writer = writerFor(mangaDir)

        val result = writer.write(defaultEdit(title = " "))

        assertEquals(LocalMangaMetadataWriteResult.BlankTitle, result)
        assertFalse(mangaDir.resolve(COMIC_INFO_FILE).exists())
    }

    @Test
    fun `fails on malformed existing ComicInfo without overwriting`() = withTempMangaDir { mangaDir ->
        val malformed = "<ComicInfo><Series>Broken"
        mangaDir.writeComicInfo(malformed)
        val writer = writerFor(mangaDir)

        val result = writer.write(defaultEdit())

        assertIs<LocalMangaMetadataWriteResult.MalformedExistingMetadata>(result)
        assertEquals(malformed, mangaDir.resolve(COMIC_INFO_FILE).readText())
    }

    @Test
    fun `does not write details json`() = withTempMangaDir { mangaDir ->
        val writer = writerFor(mangaDir)

        val result = writer.write(defaultEdit())

        assertEquals(LocalMangaMetadataWriteResult.Success, result)
        assertFalse(mangaDir.resolve("details.json").exists())
    }

    @Test
    fun `returns missing directory when manga folder cannot be found`() {
        val writer = LocalMangaMetadataWriter(
            mangaDirectoryProvider = { null },
            xml = xml,
        )

        val result = writer.write(defaultEdit())

        assertEquals(LocalMangaMetadataWriteResult.MangaDirectoryNotFound, result)
    }

    @Test
    fun `blank optional fields clear their ComicInfo values`() = withTempMangaDir { mangaDir ->
        val writer = writerFor(mangaDir)

        val result = writer.write(
            defaultEdit(
                author = " ",
                artist = "",
                description = null,
                genres = listOf(" ", ""),
            ),
        )

        assertEquals(LocalMangaMetadataWriteResult.Success, result)
        val comicInfo = mangaDir.comicInfoValues()
        assertNull(comicInfo["Writer"])
        assertNull(comicInfo["Penciller"])
        assertNull(comicInfo["Summary"])
        assertNull(comicInfo["Genre"])
    }

    private fun writerFor(mangaDir: File): LocalMangaMetadataWriter {
        return LocalMangaMetadataWriter(
            mangaDirectoryProvider = { mangaUrl ->
                if (mangaUrl == MANGA_URL) {
                    TestMangaMetadataDirectory(mangaDir)
                } else {
                    null
                }
            },
            xml = xml,
        )
    }

    private fun defaultEdit(
        title: String = "Edited Title",
        author: String? = "Edited Author",
        artist: String? = "Edited Artist",
        description: String? = "Edited Description",
        genres: List<String> = listOf("Action", "Drama"),
        status: Int = SManga.ONGOING,
    ): LocalMangaMetadataEdit {
        return LocalMangaMetadataEdit(
            mangaUrl = MANGA_URL,
            title = title,
            author = author,
            artist = artist,
            description = description,
            genres = genres,
            status = status,
        )
    }

    private fun File.writeComicInfo(content: String) {
        resolve(COMIC_INFO_FILE).writeText(content)
    }

    private fun File.comicInfoValues(): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(resolve(COMIC_INFO_FILE))
        val nodes = document.documentElement.childNodes
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                    val name = node.localName ?: node.nodeName.substringAfter(':')
                    put(name, node.textContent)
                }
            }
        }
    }

    private fun withTempMangaDir(block: (File) -> Unit) {
        val tempDir = createTempDirectory().toFile()
        val mangaDir = tempDir.resolve(MANGA_URL).apply {
            mkdirs()
        }
        try {
            block(mangaDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private companion object {
        const val MANGA_URL = "Manga Folder"

        val xml = XML {
            defaultPolicy {
                ignoreUnknownChildren()
            }
            autoPolymorphic = true
            xmlDeclMode = XmlDeclMode.Charset
            indent = 2
            xmlVersion = XmlVersion.XML10
        }
    }
}

private class TestMangaMetadataDirectory(
    private val directory: File,
) : LocalMangaMetadataDirectory {

    override fun findFile(name: String): LocalMangaMetadataFile? {
        return directory
            .resolve(name)
            .takeIf(File::exists)
            ?.let(::TestMangaMetadataFile)
    }

    override fun createFile(name: String): LocalMangaMetadataFile? {
        val file = directory.resolve(name)
        return if (file.createNewFile() || file.exists()) {
            TestMangaMetadataFile(file)
        } else {
            null
        }
    }
}

private class TestMangaMetadataFile(
    private val file: File,
) : LocalMangaMetadataFile {

    override fun openInputStream(): InputStream {
        return file.inputStream()
    }

    override fun openOutputStream(): OutputStream {
        return file.outputStream()
    }

    override fun delete(): Boolean {
        return file.delete()
    }
}
