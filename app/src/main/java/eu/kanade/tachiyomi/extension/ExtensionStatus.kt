package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.model.Extension

internal fun reconcileInstalledExtensionStatuses(
    installedExtensions: Map<String, Extension.Installed>,
    availableExtensions: List<Extension.Available>,
): Map<String, Extension.Installed> {
    return installedExtensions.mapValues { (pkgName, extension) ->
        val availableExt = availableExtensions.findMatchingAvailableExtension(pkgName, extension)

        if (availableExt == null) {
            extension.copy(
                hasUpdate = false,
                isObsolete = true,
            )
        } else {
            extension.copy(
                hasUpdate = extension.hasUpdate(availableExt),
                isObsolete = false,
                store = availableExt.store,
            )
        }
    }
}

private fun List<Extension.Available>.findMatchingAvailableExtension(
    pkgName: String,
    installedExtension: Extension.Installed,
): Extension.Available? {
    val installedStore = installedExtension.store

    return if (installedStore != null) {
        find { availableExtension ->
            availableExtension.pkgName == pkgName &&
                availableExtension.store.indexUrl == installedStore.indexUrl
        }
    } else {
        find { availableExtension ->
            availableExtension.pkgName == pkgName &&
                availableExtension.store.signingKey == installedExtension.signatureHash
        }
    }
}

private fun Extension.Installed.hasUpdate(availableExtension: Extension.Available): Boolean {
    return availableExtension.versionCode > versionCode || availableExtension.libVersion > libVersion
}
