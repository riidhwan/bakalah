package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
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
            extensionManager.untrustedExtensionsFlow,
            extensionManager.availableExtensionsFlow,
        ) {
                sensitiveExtensions,
                includeSensitiveExtensions,
                installedExtensions,
                untrustedExtensions,
                availableExtensions,
            ->
            val untrustedExtensionPackages = untrustedExtensions.map { it.pkgName }.toSet()
            val untrustedSourcesFromAvailable = availableExtensions.untrustedSources(untrustedExtensionPackages)
            val untrustedSourcePackagesFromAvailable = untrustedSourcesFromAvailable.map { it.pkgName }.toSet()
            val untrustedSources = untrustedSourcesFromAvailable +
                untrustedExtensions
                    .filterNot { extension -> extension.pkgName in untrustedSourcePackagesFromAvailable }
                    .map { extension -> extension.toUntrustedAvailableSource() }
            val sourceExtensionPackages = installedExtensions
                .flatMap { extension ->
                    extension.sources.map { source -> source.id to extension.pkgName }
                }
                .plus(untrustedSources.map { source -> source.id to source.pkgName })
                .toMap()
            SensitiveSourceSettings(
                sensitiveExtensions = sensitiveExtensions,
                includeSensitiveExtensions = includeSensitiveExtensions,
                sourceExtensionPackages = sourceExtensionPackages,
                untrustedSources = untrustedSources.map { it.toDomainSource() },
                untrustedSourceIds = untrustedSources.map { it.id }.toSet(),
            )
        }

        return combine(
            sourceListSettings,
            sensitiveSourceSettings,
            repository.getOnlineSources(),
        ) { sourceListSettings, sensitiveSettings, sources ->
            (sources + sensitiveSettings.untrustedSources)
                .distinctBy { it.id }
                .filter { source ->
                    source.lang in sourceListSettings.enabledLanguages ||
                        source.id in sensitiveSettings.untrustedSourceIds
                }
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
        val untrustedSources: List<Source>,
        val untrustedSourceIds: Set<Long>,
    )

    private data class UntrustedAvailableSource(
        val id: Long,
        val lang: String,
        val name: String,
        val pkgName: String,
    ) {
        fun toDomainSource(): Source {
            return Source(
                id = id,
                lang = lang,
                name = name,
                supportsLatest = false,
                isStub = true,
            )
        }
    }

    private fun List<Extension.Available>.untrustedSources(
        untrustedExtensionPackages: Set<String>,
    ): List<UntrustedAvailableSource> {
        return filter { extension -> extension.pkgName in untrustedExtensionPackages }
            .flatMap { extension ->
                extension.sources.map { source ->
                    UntrustedAvailableSource(
                        id = source.id,
                        lang = source.lang,
                        name = source.name,
                        pkgName = extension.pkgName,
                    )
                }
            }
    }

    private fun Extension.Untrusted.toUntrustedAvailableSource(): UntrustedAvailableSource {
        return UntrustedAvailableSource(
            id = untrustedSourceId(pkgName),
            lang = untrustedSourceLang(),
            name = name,
            pkgName = pkgName,
        )
    }

    private fun untrustedSourceId(pkgName: String): Long {
        return Long.MIN_VALUE + pkgName.hashCode().toUInt().toLong()
    }

    private fun Extension.Untrusted.untrustedSourceLang(): String {
        return lang ?: pkgName.substringAfter(".extension.", "")
            .substringBefore(".")
            .takeIf { it.isNotBlank() }
            .orEmpty()
    }
}
