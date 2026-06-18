package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

        combine(
            extensionManager.installedExtensionsFlow,
            extensionManager.untrustedExtensionsFlow,
            extensionManager.availableExtensionsFlow,
        ) { installedExtensions, untrustedExtensions, availableExtensions ->
            val untrustedExtensionPackages = untrustedExtensions.map { it.pkgName }.toSet()
            val untrustedSourceExtensionPackagesFromAvailable = availableExtensions
                .filter { extension -> extension.pkgName in untrustedExtensionPackages }
                .flatMap { extension ->
                    extension.sources.map { source -> source.id to extension.pkgName }
                }
                .toMap()
            val untrustedSourcePackagesFromAvailable = untrustedSourceExtensionPackagesFromAvailable.values.toSet()
            ExtensionSourceMetadata(
                installedExtensions = installedExtensions,
                untrustedSourceExtensionPackages = untrustedSourceExtensionPackagesFromAvailable +
                    untrustedExtensions
                        .filterNot { extension -> extension.pkgName in untrustedSourcePackagesFromAvailable }
                        .associate { extension -> untrustedSourceId(extension.pkgName) to extension.pkgName },
            )
        }
            .onEach { metadata ->
                val sourceExtensionPackages = metadata.installedExtensions
                    .flatMap { extension ->
                        extension.sources.map { source -> source.id to extension.pkgName }
                    }
                    .plus(metadata.untrustedSourceExtensionPackages.toList())
                    .toMap()
                val obsoleteSourceIds = metadata.installedExtensions
                    .filter { it.isObsolete }
                    .flatMap { extension -> extension.sources.map { it.id } }
                    .toSet()
                val sourceIdsWithUpdate = metadata.installedExtensions
                    .filter { it.hasUpdate }
                    .flatMap { extension -> extension.sources.map { it.id } }
                    .toSet()
                mutableState.update { state ->
                    state.copy(
                        obsoleteSourceIds = obsoleteSourceIds,
                        sourceExtensionPackages = sourceExtensionPackages,
                        sourceIdsWithUpdate = sourceIdsWithUpdate,
                        untrustedSourceIds = metadata.untrustedSourceExtensionPackages.keys,
                    )
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

    fun updateExtension(source: Source) {
        val extension = extensionManager.installedExtensionsFlow.value
            .firstOrNull { extension ->
                extension.hasUpdate && extension.sources.any { it.id == source.id }
            }
            ?: return

        screenModelScope.launchIO {
            extensionManager.updateExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun getUntrustedExtension(source: Source): Extension.Untrusted? {
        val pkgName = mutableState.value.sourceExtensionPackage(source) ?: return null
        return extensionManager.untrustedExtensionsFlow.value.firstOrNull { it.pkgName == pkgName }
    }

    fun trustExtension(extension: Extension.Untrusted) {
        screenModelScope.launch {
            extensionManager.trust(extension)
        }
    }

    fun uninstallExtension(extension: Extension.Untrusted) {
        extensionManager.uninstallExtension(extension)
    }

    fun showSourceDialog(source: Source) {
        mutableState.update { it.copy(dialog = Dialog(source)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    private fun addUpdatingExtension(extension: Extension) {
        mutableState.update { state ->
            state.copy(updatingExtensionPackages = state.updatingExtensionPackages + extension.pkgName)
        }
    }

    private fun removeUpdatingExtension(extension: Extension) {
        mutableState.update { state ->
            state.copy(updatingExtensionPackages = state.updatingExtensionPackages - extension.pkgName)
        }
    }

    private suspend fun Flow<InstallStep>.collectToInstallUpdate(extension: Extension) =
        this
            .onEach { installStep ->
                if (installStep == InstallStep.Idle) {
                    removeUpdatingExtension(extension)
                } else {
                    addUpdatingExtension(extension)
                }
            }
            .takeWhile { installStep -> installStep != InstallStep.Installed }
            .onCompletion { removeUpdatingExtension(extension) }
            .collect()

    sealed interface Event {
        data object FailedFetchingSources : Event
    }

    data class Dialog(val source: Source)

    private data class ExtensionSourceMetadata(
        val installedExtensions: List<Extension.Installed>,
        val untrustedSourceExtensionPackages: Map<Long, String>,
    )

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val isLoading: Boolean = true,
        val languages: List<String> = listOf(),
        val selectedLanguage: String? = null,
        val includeSensitiveExtensions: Boolean = false,
        val sensitiveExtensions: Set<String> = emptySet(),
        val updatingExtensionPackages: Set<String> = emptySet(),
        private val obsoleteSourceIds: Set<Long> = emptySet(),
        private val sourceIdsWithUpdate: Set<Long> = emptySet(),
        private val sourceExtensionPackages: Map<Long, String> = emptyMap(),
        private val untrustedSourceIds: Set<Long> = emptySet(),
        private val sources: List<Source> = listOf(),
    ) {
        val isEmpty = languages.isEmpty()

        val items: List<Source>
            get() = sources
                .filter { source ->
                    source.lang == selectedLanguage || source.id in untrustedSourceIds
                }
                .sortedWith(sourceComparator)

        val totalUpdateCount: Int
            get() = sources.count { source ->
                source.id in sourceIdsWithUpdate
            }

        fun isSensitiveExtensionPackage(pkgName: String): Boolean {
            return pkgName in sensitiveExtensions
        }

        fun isObsoleteSource(source: Source): Boolean {
            return source.id in obsoleteSourceIds
        }

        fun isSensitiveSource(source: Source): Boolean {
            return sourceExtensionPackages[source.id] in sensitiveExtensions
        }

        fun isUntrustedSource(source: Source): Boolean {
            return source.id in untrustedSourceIds
        }

        fun isUpdateAvailable(source: Source): Boolean {
            return source.id in sourceIdsWithUpdate
        }

        fun updateCountForLanguage(language: String): Int {
            return sources.count { source ->
                source.lang == language && source.id in sourceIdsWithUpdate
            }
        }

        fun isUpdating(source: Source): Boolean {
            return sourceExtensionPackages[source.id] in updatingExtensionPackages
        }

        fun sourceExtensionPackage(source: Source): String? {
            return sourceExtensionPackages[source.id]
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

        private fun untrustedSourceId(pkgName: String): Long {
            return Long.MIN_VALUE + pkgName.hashCode().toUInt().toLong()
        }
    }
}
