package eu.kanade.tachiyomi.data.vault.remote

internal fun String.childPath(child: String): String = "${trimEnd('/')}/$child".trimStart('/')
