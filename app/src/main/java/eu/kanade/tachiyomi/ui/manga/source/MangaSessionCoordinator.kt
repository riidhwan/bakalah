package eu.kanade.tachiyomi.ui.manga.source

import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.tachiyomi.data.diagnostic.PersistenceDiagnosticRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import tachiyomi.core.common.util.lang.launchIO

internal class MangaSessionCoordinator(
    private val dependencies: Dependencies,
) {

    fun start(
        runtime: Runtime,
        activeMangaId: Flow<Long>,
        callbacks: Callbacks,
    ) {
        observeManga(runtime, activeMangaId, callbacks)
        observeExcludedScanlators(runtime, activeMangaId, callbacks)
        observeAvailableScanlators(runtime, activeMangaId, callbacks)
    }

    private fun observeManga(
        runtime: Runtime,
        activeMangaId: Flow<Long>,
        callbacks: Callbacks,
    ) {
        runtime.screenModelScope.launchIO {
            val initialSnapshotTrace = dependencies.persistenceDiagnostics.begin(
                PersistenceDiagnosticRecorder.MANGA_INITIAL_SNAPSHOT,
            )
            try {
                dependencies.loadCoordinator.observe(activeMangaId)
                    .collect { snapshot ->
                        initialSnapshotTrace.finish()
                        callbacks.onSnapshot(snapshot)

                        if (dependencies.loadCoordinator.takeRefreshOnLoad(
                                snapshot,
                                runtime.screenModelScope.isActive,
                            )
                        ) {
                            callbacks.onRefreshOnLoad(snapshot)
                        }
                    }
            } catch (error: CancellationException) {
                initialSnapshotTrace.cancel()
                throw error
            } catch (error: Throwable) {
                initialSnapshotTrace.fail()
                if (!callbacks.isDeletingLocalManga()) throw error
            } finally {
                initialSnapshotTrace.cancel()
            }
        }
    }

    private fun observeExcludedScanlators(
        runtime: Runtime,
        activeMangaId: Flow<Long>,
        callbacks: Callbacks,
    ) {
        runtime.screenModelScope.launchIO {
            activeMangaId
                .flatMapLatest { dependencies.getExcludedScanlators.subscribe(it) }
                .distinctUntilChanged()
                .collectLatest(callbacks.onExcludedScanlators)
        }
    }

    private fun observeAvailableScanlators(
        runtime: Runtime,
        activeMangaId: Flow<Long>,
        callbacks: Callbacks,
    ) {
        runtime.screenModelScope.launchIO {
            activeMangaId
                .flatMapLatest { dependencies.getAvailableScanlators.subscribe(it) }
                .distinctUntilChanged()
                .collectLatest(callbacks.onAvailableScanlators)
        }
    }

    data class Runtime(
        val screenModelScope: CoroutineScope,
    )

    data class Dependencies(
        val loadCoordinator: MangaLoadCoordinator,
        val getAvailableScanlators: GetAvailableScanlators,
        val getExcludedScanlators: GetExcludedScanlators,
        val persistenceDiagnostics: PersistenceDiagnosticRecorder,
    )

    data class Callbacks(
        val isDeletingLocalManga: () -> Boolean,
        val onSnapshot: suspend (MangaLoadSnapshot) -> Unit,
        val onRefreshOnLoad: suspend (MangaLoadSnapshot) -> Unit,
        val onExcludedScanlators: suspend (Set<String>) -> Unit,
        val onAvailableScanlators: suspend (Set<String>) -> Unit,
    )
}
