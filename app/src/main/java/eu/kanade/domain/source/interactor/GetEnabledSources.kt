package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository

class GetEnabledSources(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
) {

    fun subscribe(): Flow<List<Source>> {
        val sourceListSettings = combine(
            preferences.pinnedSources.changes(),
            preferences.enabledLanguages.changes(),
            preferences.disabledSources.changes(),
            preferences.lastUsedSource.changes(),
        ) { pinnedSourceIds, enabledLanguages, disabledSources, lastUsedSource ->
            SourceListSettings(pinnedSourceIds, enabledLanguages, disabledSources, lastUsedSource)
        }
        val sensitiveSourceSettings = combine(
            preferences.sensitiveExtensions.changes(),
            preferences.includeSensitiveExtensions.changes(),
            extensionManager.installedExtensionsFlow,
        ) { sensitiveExtensions, includeSensitiveExtensions, installedExtensions ->
            val sourceExtensionPackages = installedExtensions
                .flatMap { extension ->
                    extension.sources.map { source -> source.id to extension.pkgName }
                }
                .toMap()
            SensitiveSourceSettings(sensitiveExtensions, includeSensitiveExtensions, sourceExtensionPackages)
        }

        return combine(
            sourceListSettings,
            sensitiveSourceSettings,
            repository.getOnlineSources(),
        ) { sourceListSettings, sensitiveSettings, sources ->
            sources
                .filter { it.lang in sourceListSettings.enabledLanguages }
                .filterNot { it.id.toString() in sourceListSettings.disabledSources }
                .filterNot { source ->
                    !sensitiveSettings.includeSensitiveExtensions &&
                        sensitiveSettings.sourceExtensionPackages[source.id] in sensitiveSettings.sensitiveExtensions
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .flatMap {
                    val flag = if ("${it.id}" in sourceListSettings.pinnedSourceIds) Pins.pinned else Pins.unpinned
                    val source = it.copy(pin = flag)
                    val toFlatten = mutableListOf(source)
                    if (source.id == sourceListSettings.lastUsedSource) {
                        toFlatten.add(source.copy(isUsedLast = true, pin = source.pin - Pin.Actual))
                    }
                    toFlatten
                }
        }
            .distinctUntilChanged()
    }

    private data class SourceListSettings(
        val pinnedSourceIds: Set<String>,
        val enabledLanguages: Set<String>,
        val disabledSources: Set<String>,
        val lastUsedSource: Long,
    )

    private data class SensitiveSourceSettings(
        val sensitiveExtensions: Set<String>,
        val includeSensitiveExtensions: Boolean,
        val sourceExtensionPackages: Map<Long, String>,
    )
}
