package eu.kanade.tachiyomi.data.vault.staging

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.Format
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class FileDigest(
    val sizeBytes: Long,
    val sha256: String,
)

internal fun UniFile.digest(): FileDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    if (isDirectory) {
        listFilesRecursively().forEach { file ->
            val relativePath = file.relativePathFrom(this)
            digest.update(relativePath.toByteArray())
            digest.update(0.toByte())
            size += file.updateDigest(digest)
        }
    } else {
        size = updateDigest(digest)
    }
    return FileDigest(size, digest.digest().toHex())
}

internal fun UniFile.previewDigest(): FileDigest {
    if (!isDirectory) return digest()

    return FileDigest(
        sizeBytes = length().takeIf { it >= 0 } ?: 0,
        sha256 = "$PENDING_DIRECTORY_CBZ_CHECKSUM_PREFIX$uri",
    )
}

private fun UniFile.updateDigest(digest: MessageDigest): Long {
    var size = 0L
    openInputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            size += read
        }
    }
    return size
}

internal fun UniFile.asRequestBody(): RequestBody {
    return object : RequestBody() {
        override fun contentType() = OCTET_MEDIA_TYPE

        override fun contentLength(): Long = length().takeIf { it >= 0 } ?: -1

        override fun writeTo(sink: BufferedSink) {
            openInputStream().use { input ->
                sink.writeAll(input.source())
            }
        }
    }
}

internal fun UniFile.listFilesRecursively(): List<UniFile> {
    return listFiles().orEmpty()
        .flatMap { file -> if (file.isDirectory) file.listFilesRecursively() else listOf(file) }
        .sortedWith { first, second ->
            first.relativePathFrom(this).compareToCaseInsensitiveNaturalOrder(second.relativePathFrom(this))
        }
}

internal fun UniFile.listReadablePageFilesRecursively(): List<UniFile> {
    return listFilesRecursively()
        .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
}

internal fun UniFile.isCbz(): Boolean {
    return name.orEmpty().substringAfterLast('.', missingDelimiterValue = "").equals("cbz", ignoreCase = true) &&
        Format.valueOf(this) is Format.Archive
}

internal fun UniFile.coverMediaType(): String? {
    return when (extension?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }
}

internal fun UniFile.relativePathFrom(root: UniFile): String {
    return relativePathFromUriStrings(root.uri.toString(), uri.toString())
}

internal fun relativePathFromUriStrings(rootUri: String, fileUri: String): String {
    return fileUri
        .decodePercentEscapes()
        .removePrefix(rootUri.decodePercentEscapes().trimEnd('/', '\\'))
        .trimStart('/', '\\')
}

internal fun UniFile.deleteRecursively() {
    if (isDirectory) {
        listFiles().orEmpty().forEach { it.deleteRecursively() }
    }
    delete()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.decodePercentEscapes(): String {
    val result = StringBuilder(length)
    val bytes = ByteArrayOutputStream()

    fun flushBytes() {
        if (bytes.size() > 0) {
            result.append(bytes.toByteArray().toString(StandardCharsets.UTF_8))
            bytes.reset()
        }
    }

    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            val value = substring(index + 1, index + 3).toIntOrNull(16)
            if (value != null) {
                bytes.write(value)
                index += 3
                continue
            }
        }
        flushBytes()
        result.append(char)
        index++
    }
    flushBytes()
    return result.toString()
}

private val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()
private const val PENDING_DIRECTORY_CBZ_CHECKSUM_PREFIX = "pending-directory-cbz:"
