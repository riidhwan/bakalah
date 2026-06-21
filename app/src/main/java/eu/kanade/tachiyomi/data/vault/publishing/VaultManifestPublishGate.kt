package eu.kanade.tachiyomi.data.vault.publishing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.vault.model.ContentVaultIdentity
import java.util.concurrent.ConcurrentHashMap

class VaultManifestPublishGate {
    private val mutexes = ConcurrentHashMap<ContentVaultIdentity, Mutex>()

    suspend fun <T> withGate(identity: ContentVaultIdentity, block: suspend () -> T): T {
        val mutex = mutexes.getOrPut(identity) { Mutex() }
        return mutex.withLock {
            block()
        }
    }
}
