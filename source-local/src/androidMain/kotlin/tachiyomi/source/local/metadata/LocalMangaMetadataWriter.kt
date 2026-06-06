package tachiyomi.source.local.metadata

import com.hippo.unifile.UniFile
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

class LocalMangaMetadataWriter private constructor(
    private val mangaDirectoryProvider: (String) -> LocalMangaMetadataDirectory?,
    private val xml: XML,
) {

    constructor(
        fileSystem: LocalSourceFileSystem,
        xml: XML,
    ) : this(
        mangaDirectoryProvider = { mangaUrl ->
            fileSystem.getMangaDirectory(mangaUrl)?.let(::UniFileMangaMetadataDirectory)
        },
        xml = xml,
    )

    internal constructor(
        mangaDirectoryProvider: (String) -> LocalMangaMetadataDirectory?,
        xml: XML,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(mangaDirectoryProvider, xml)

    fun write(edit: LocalMangaMetadataEdit): LocalMangaMetadataWriteResult {
        val title = edit.title.trim()
        if (title.isBlank()) {
            return LocalMangaMetadataWriteResult.BlankTitle
        }

        val mangaDir = mangaDirectoryProvider(edit.mangaUrl)
            ?: return LocalMangaMetadataWriteResult.MangaDirectoryNotFound

        val existingComicInfoFile = mangaDir.findFile(COMIC_INFO_FILE)
        val existingComicInfo = if (existingComicInfoFile != null) {
            when (val result = existingComicInfoFile.readComicInfo()) {
                is ReadComicInfoResult.Success -> result.comicInfo
                is ReadComicInfoResult.Malformed -> {
                    return LocalMangaMetadataWriteResult.MalformedExistingMetadata(result.cause)
                }
            }
        } else {
            emptyComicInfo()
        }

        val comicInfo = existingComicInfo.withEdit(edit, title)
        val targetFile = existingComicInfoFile.prepareForWrite(mangaDir)
            ?: return LocalMangaMetadataWriteResult.WriteFailure()

        return try {
            targetFile.openOutputStream().use { outputStream ->
                outputStream.write(
                    xml.encodeToString(ComicInfo.serializer(), comicInfo)
                        .toByteArray(StandardCharsets.UTF_8),
                )
            }
            mangaDir.findFile(NO_XML_FILE)?.delete()
            LocalMangaMetadataWriteResult.Success
        } catch (e: Throwable) {
            LocalMangaMetadataWriteResult.WriteFailure(e)
        }
    }

    private fun LocalMangaMetadataFile?.prepareForWrite(
        mangaDir: LocalMangaMetadataDirectory,
    ): LocalMangaMetadataFile? {
        if (this == null) {
            return mangaDir.createFile(COMIC_INFO_FILE)
        }

        return try {
            truncate()
            this
        } catch (_: Throwable) {
            if (!delete()) return null
            mangaDir.createFile(COMIC_INFO_FILE)
        }
    }

    private fun LocalMangaMetadataFile.readComicInfo(): ReadComicInfoResult {
        return try {
            val comicInfo = openInputStream().use { stream ->
                parseComicInfo(stream)
            }
            ReadComicInfoResult.Success(comicInfo)
        } catch (e: Throwable) {
            ReadComicInfoResult.Malformed(e)
        }
    }

    private fun parseComicInfo(stream: InputStream): ComicInfo {
        val bytes = stream.readBytes()
        return try {
            parseComicInfoBytes(bytes)
        } catch (e: Throwable) {
            val recoveredBytes = bytes.removeStaleTrailingComicInfoBytes()
                ?: throw e
            parseComicInfoBytes(recoveredBytes)
        }
    }

    private fun parseComicInfoBytes(bytes: ByteArray): ComicInfo {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val elementsByName = document
            .documentElement
            .childNodes
            .let { nodes ->
                buildMap {
                    for (index in 0 until nodes.length) {
                        val node = nodes.item(index)
                        if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                            val name = node.localName ?: node.nodeName.substringAfter(':')
                            put(name, node.textContent)
                        }
                    }
                }
            }

        return ComicInfo(
            title = elementsByName["Title"]?.let(ComicInfo::Title),
            series = elementsByName["Series"]?.let(ComicInfo::Series),
            number = elementsByName["Number"]?.let(ComicInfo::Number),
            summary = elementsByName["Summary"]?.let(ComicInfo::Summary),
            writer = elementsByName["Writer"]?.let(ComicInfo::Writer),
            penciller = elementsByName["Penciller"]?.let(ComicInfo::Penciller),
            inker = elementsByName["Inker"]?.let(ComicInfo::Inker),
            colorist = elementsByName["Colorist"]?.let(ComicInfo::Colorist),
            letterer = elementsByName["Letterer"]?.let(ComicInfo::Letterer),
            coverArtist = elementsByName["CoverArtist"]?.let(ComicInfo::CoverArtist),
            translator = elementsByName["Translator"]?.let(ComicInfo::Translator),
            genre = elementsByName["Genre"]?.let(ComicInfo::Genre),
            tags = elementsByName["Tags"]?.let(ComicInfo::Tags),
            web = elementsByName["Web"]?.let(ComicInfo::Web),
            publishingStatus = elementsByName["PublishingStatusTachiyomi"]?.let(
                ComicInfo::PublishingStatusTachiyomi,
            ),
            categories = elementsByName["Categories"]?.let(ComicInfo::CategoriesTachiyomi),
            source = elementsByName["SourceMihon"]?.let(ComicInfo::SourceMihon),
        )
    }

    private fun ByteArray.removeStaleTrailingComicInfoBytes(): ByteArray? {
        val content = toString(StandardCharsets.UTF_8)
        val documentEnd = content.indexOf(COMIC_INFO_END_TAG)
            .takeIf { it >= 0 }
            ?.plus(COMIC_INFO_END_TAG.length)
            ?: return null

        if (content.substring(documentEnd).isBlank()) {
            return null
        }

        return content
            .substring(0, documentEnd)
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun ComicInfo.withEdit(
        edit: LocalMangaMetadataEdit,
        title: String,
    ): ComicInfo {
        return copy(
            series = ComicInfo.Series(title),
            summary = edit.description.toComicInfoValue(ComicInfo::Summary),
            writer = edit.author.toComicInfoValue(ComicInfo::Writer),
            penciller = edit.artist.toComicInfoValue(ComicInfo::Penciller),
            genre = edit.genres
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString()
                .toComicInfoValue(ComicInfo::Genre),
            publishingStatus = ComicInfo.PublishingStatusTachiyomi(
                ComicInfoPublishingStatus.toComicInfoValue(edit.status.toLong()),
            ),
        )
    }

    private fun emptyComicInfo(): ComicInfo {
        return ComicInfo(
            title = null,
            series = null,
            number = null,
            summary = null,
            writer = null,
            penciller = null,
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = null,
            genre = null,
            tags = null,
            web = null,
            publishingStatus = null,
            categories = null,
            source = null,
        )
    }

    private fun <T> String?.toComicInfoValue(factory: ComicInfoFactory<T>): T? {
        return this
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(factory::create)
    }

    private sealed interface ReadComicInfoResult {
        data class Success(val comicInfo: ComicInfo) : ReadComicInfoResult
        data class Malformed(val cause: Throwable) : ReadComicInfoResult
    }

    private fun interface ComicInfoFactory<T> {
        fun create(value: String): T
    }

    private companion object {
        const val NO_XML_FILE = ".noxml"
        const val COMIC_INFO_END_TAG = "</ComicInfo>"
    }
}

internal interface LocalMangaMetadataDirectory {
    fun findFile(name: String): LocalMangaMetadataFile?

    fun createFile(name: String): LocalMangaMetadataFile?
}

internal interface LocalMangaMetadataFile {
    fun openInputStream(): InputStream

    fun truncate()

    fun openOutputStream(): OutputStream

    fun delete(): Boolean
}

private class UniFileMangaMetadataDirectory(
    private val directory: UniFile,
) : LocalMangaMetadataDirectory {

    override fun findFile(name: String): LocalMangaMetadataFile? {
        return directory.findFile(name)?.let(::UniFileMangaMetadataFile)
    }

    override fun createFile(name: String): LocalMangaMetadataFile? {
        return directory.createFile(name)?.let(::UniFileMangaMetadataFile)
    }
}

private class UniFileMangaMetadataFile(
    private val file: UniFile,
) : LocalMangaMetadataFile {

    override fun openInputStream(): InputStream {
        return file.openInputStream()
    }

    override fun truncate() {
        val randomAccessFile = file.createRandomAccessFile("rw")
        try {
            randomAccessFile.setLength(0)
        } finally {
            randomAccessFile.close()
        }
    }

    override fun openOutputStream(): OutputStream {
        return file.openOutputStream()
    }

    override fun delete(): Boolean {
        return file.delete()
    }
}

data class LocalMangaMetadataEdit(
    val mangaUrl: String,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genres: List<String>,
    val status: Int,
)

sealed interface LocalMangaMetadataWriteResult {
    data object Success : LocalMangaMetadataWriteResult
    data object BlankTitle : LocalMangaMetadataWriteResult
    data object MangaDirectoryNotFound : LocalMangaMetadataWriteResult
    data class MalformedExistingMetadata(val cause: Throwable) : LocalMangaMetadataWriteResult
    data class WriteFailure(val cause: Throwable? = null) : LocalMangaMetadataWriteResult
}
