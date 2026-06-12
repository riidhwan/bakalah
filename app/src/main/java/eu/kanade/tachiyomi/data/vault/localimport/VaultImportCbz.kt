package eu.kanade.tachiyomi.data.vault.localimport

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class CbzEntry(
    val name: String,
    val openInputStream: () -> InputStream,
)

internal fun collisionSafeCbzName(baseName: String, existingNames: Set<String>): String {
    val sanitized = baseName
        .trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim('.')
        .ifBlank { "chapter" }
    val base = sanitized.removeSuffix(".cbz")
    var candidate = "$base.cbz"
    var index = 1
    while (existingNames.any { it.equals(candidate, ignoreCase = true) }) {
        candidate = "$base ($index).cbz"
        index++
    }
    return candidate
}

internal fun directoryChapterCbzBaseName(directoryName: String?): String {
    return directoryName.orEmpty()
}

internal fun cbzEntryName(name: String): String {
    return name
        .trim()
        .replace('\\', '/')
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/")
}

internal fun numberedCbzEntryName(index: Int, extension: String?): String {
    return "%03d.%s".format(
        Locale.ENGLISH,
        index,
        extension?.lowercase()?.takeIf { it.isNotBlank() } ?: "jpg",
    )
}

internal fun writeStoredCbz(
    output: OutputStream,
    entries: List<CbzEntry>,
) {
    ZipOutputStream(output).use { zip ->
        entries.forEach { entry ->
            val bytes = entry.openInputStream().use { it.readBytes() }
            val crc = CRC32().apply { update(bytes) }
            val zipEntry = ZipEntry(cbzEntryName(entry.name)).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            }
            zip.putNextEntry(zipEntry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

internal fun validateCbz(file: com.hippo.unifile.UniFile, expectedEntries: List<String>) {
    require(file.length() > 0) { "CBZ is empty" }
    val entries = ZipInputStream(file.openInputStream()).use { zip ->
        generateSequence { zip.nextEntry }
            .map { it.name }
            .toList()
    }
    require(entries == expectedEntries) { "CBZ entries did not validate" }
}
