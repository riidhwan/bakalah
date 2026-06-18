package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined._18UpRating
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.ExtensionTrustDialog
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

@Composable
fun Screen.sourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { SourcesScreenModel() }
    val state by screenModel.state.collectAsState()
    var untrustedExtensionToTrust by remember { mutableStateOf<Extension.Untrusted?>(null) }

    return TabContent(
        titleRes = MR.strings.label_sources,
        badgeNumber = state.totalUpdateCount.takeIf { it > 0 },
        actions = listOf(
            AppBar.Action(
                title = stringResource(
                    if (state.includeSensitiveExtensions) {
                        MR.strings.vault_action_hide_sensitive
                    } else {
                        MR.strings.vault_action_include_sensitive
                    },
                ),
                icon = Icons.Outlined._18UpRating,
                iconTint = if (state.includeSensitiveExtensions) {
                    MaterialTheme.colorScheme.active
                } else {
                    LocalContentColor.current
                },
                onClick = {
                    screenModel.setIncludeSensitiveExtensions(!state.includeSensitiveExtensions)
                },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalSearchScreen()) },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.push(SourcesFilterScreen()) },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            SourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    navigator.push(BrowseSourceScreen(source.id, listing.query))
                },
                onClickUpdateExtension = screenModel::updateExtension,
                onClickTrust = { source ->
                    untrustedExtensionToTrust = screenModel.getUntrustedExtension(source)
                },
                onLongClickItem = screenModel::showSourceDialog,
                onLanguageFilterChange = screenModel::setLanguageFilter,
            )

            state.dialog?.let { dialog ->
                val source = dialog.source
                val extensionPackage = screenModel.getExtensionPackage(source)
                SourceOptionsDialog(
                    source = source,
                    isSensitive = extensionPackage?.let(state::isSensitiveExtensionPackage),
                    onClickPin = {
                        screenModel.togglePin(source)
                        screenModel.closeDialog()
                    },
                    onClickExtensionInfo = extensionPackage?.let { pkgName ->
                        {
                            navigator.push(ExtensionDetailsScreen(pkgName))
                            screenModel.closeDialog()
                        }
                    },
                    onClickDisable = {
                        screenModel.toggleSource(source)
                        screenModel.closeDialog()
                    },
                    onClickSensitive = { sensitive ->
                        screenModel.setSourceSensitive(source, sensitive)
                        screenModel.closeDialog()
                    },
                    onDismiss = screenModel::closeDialog,
                )
            }

            untrustedExtensionToTrust?.let { extension ->
                ExtensionTrustDialog(
                    onClickConfirm = {
                        screenModel.trustExtension(extension)
                        untrustedExtensionToTrust = null
                    },
                    onClickDismiss = {
                        screenModel.uninstallExtension(extension)
                        untrustedExtensionToTrust = null
                    },
                    onDismissRequest = {
                        untrustedExtensionToTrust = null
                    },
                )
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        SourcesScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
