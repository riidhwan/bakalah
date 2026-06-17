package eu.kanade.tachiyomi.data.vault.remote.webdav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

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
