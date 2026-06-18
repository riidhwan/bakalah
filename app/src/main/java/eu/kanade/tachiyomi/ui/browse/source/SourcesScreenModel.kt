package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourcesScreenModel(
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val toggleSource: ToggleSource = Injekt.get(),
    private val toggleSourcePin: ToggleSourcePin = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
) : StateScreenModel<SourcesScreenModel.State>(State()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            getEnabledSources.subscribe()
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(Event.FailedFetchingSources)
                }
                .collectLatest(::collectLatestSources)
        }

        sourcePreferences.includeSensitiveExtensions.changes()
            .onEach { includeSensitiveExtensions ->
                mutableState.update { state ->
                    state.copy(includeSensitiveExtensions = includeSensitiveExtensions)
                }
            }
            .launchIn(screenModelScope)

        sourcePreferences.sensitiveExtensions.changes()
            .onEach { sensitiveExtensions ->
                mutableState.update { state ->
                    state.copy(sensitiveExtensions = sensitiveExtensions)
                }
            }
            .launchIn(screenModelScope)
    }

    private fun collectLatestSources(sources: List<Source>) {
        mutableState.update { state ->
            val sourceItems = sources.filterNot { it.isUsedLast }
            val languages = sourceItems
                .map { it.lang }
                .distinct()
                .sortedWith(sourceLanguageComparator)
            val selectedLanguage = state.selectedLanguage
                ?.takeIf { it in languages }
                ?: defaultSelectedLanguage(languages)

            state.copy(
                isLoading = false,
                languages = languages,
                selectedLanguage = selectedLanguage,
                sources = sourceItems,
            )
        }
    }

    fun setLanguageFilter(language: String) {
        mutableState.update { state ->
            state.copy(selectedLanguage = language)
        }
    }

    fun toggleSource(source: Source) {
        toggleSource.await(source)
    }

    fun togglePin(source: Source) {
        toggleSourcePin.await(source)
    }

    fun setIncludeSensitiveExtensions(includeSensitiveExtensions: Boolean) {
        sourcePreferences.includeSensitiveExtensions.set(includeSensitiveExtensions)
    }

    fun getExtensionPackage(source: Source): String? {
        return extensionManager.getExtensionPackage(source.id)
    }

    fun setSourceSensitive(source: Source, sensitive: Boolean) {
        val pkgName = getExtensionPackage(source) ?: return
        if (sensitive) {
            sourcePreferences.markExtensionSensitive(pkgName)
        } else {
            sourcePreferences.unmarkExtensionSensitive(pkgName)
        }
    }

    fun showSourceDialog(source: Source) {
        mutableState.update { it.copy(dialog = Dialog(source)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Event {
        data object FailedFetchingSources : Event
    }

    data class Dialog(val source: Source)

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val isLoading: Boolean = true,
        val languages: List<String> = listOf(),
        val selectedLanguage: String? = null,
        val includeSensitiveExtensions: Boolean = false,
        val sensitiveExtensions: Set<String> = emptySet(),
        private val sources: List<Source> = listOf(),
    ) {
        val isEmpty = languages.isEmpty()

        val items: List<Source>
            get() = sources
                .filter { it.lang == selectedLanguage }
                .sortedWith(sourceComparator)

        fun isSensitiveExtensionPackage(pkgName: String): Boolean {
            return pkgName in sensitiveExtensions
        }
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"

        private val sourceLanguageComparator = Comparator<String> { left, right ->
            // Sources without a lang defined will be placed at the end.
            when {
                left == "" && right != "" -> 1
                right == "" && left != "" -> -1
                else -> LocaleHelper.comparator(left, right)
            }
        }

        private val sourceComparator = compareBy<Source> { Pin.Actual !in it.pin }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        private fun defaultSelectedLanguage(languages: List<String>): String? {
            return when {
                "en" in languages -> "en"
                else -> languages.firstOrNull()
            }
        }
    }
}
