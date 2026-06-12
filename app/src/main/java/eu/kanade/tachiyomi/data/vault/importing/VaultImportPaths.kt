package eu.kanade.tachiyomi.data.vault.importing

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal fun String.resolveWebDavPath(path: String, collection: Boolean = false): HttpUrl {
    val builder = trim().trimEnd('/').toHttpUrl().newBuilder()
    path.trim().trim('/')
        .split('/')
        .filter { it.isNotBlank() }
        .forEach { builder.addPathSegment(it) }
    if (collection) {
        builder.addPathSegment("")
    }
    return builder.build()
}

internal fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')

internal fun relativePathFromUriStrings(rootUri: String, fileUri: String): String {
    return fileUri
        .decodePercentEscapes()
        .removePrefix(rootUri.decodePercentEscapes().trimEnd('/', '\\'))
        .trimStart('/', '\\')
}

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
