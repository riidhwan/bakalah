package eu.kanade.tachiyomi.ui.manga.tracking

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.manga.model.MangaTrackingUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.core.common.util.lang.launchIO

internal class MangaTrackingObserver(
    private val trackingCoordinator: MangaTrackingCoordinator,
) {
    private var trackerJob: Job? = null

    fun restart(
        screenModelScope: CoroutineScope,
        lifecycle: Lifecycle,
        mangaId: Long,
        source: Source,
        onTrackingUiState: (MangaTrackingUiState) -> Unit,
    ) {
        trackerJob?.cancel()
        trackerJob = screenModelScope.launchIO {
            trackingCoordinator.observeTrackingState(mangaId, source)
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { trackingState ->
                    onTrackingUiState(
                        MangaTrackingUiState(
                            count = trackingState.trackingCount,
                            hasLoggedInTrackers = trackingState.hasLoggedInTrackers,
                        ),
                    )
                }
        }
    }
}
