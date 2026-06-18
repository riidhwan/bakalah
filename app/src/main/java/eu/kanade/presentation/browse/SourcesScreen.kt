package eu.kanade.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.BaseSourceItem
import eu.kanade.presentation.manga.components.DotSeparatorNoSpaceText
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.source.local.isLocal

@Composable
fun SourcesScreen(
    state: SourcesScreenModel.State,
    contentPadding: PaddingValues,
    onClickItem: (Source, Listing) -> Unit,
    onClickPin: (Source) -> Unit,
    onLongClickItem: (Source) -> Unit,
    onLanguageFilterChange: (String) -> Unit,
) {
    when {
        state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
        state.isEmpty -> EmptyScreen(
            stringRes = MR.strings.source_empty_screen,
            modifier = Modifier.padding(contentPadding),
        )
        else -> {
            ScrollbarLazyColumn(
                contentPadding = contentPadding + topSmallPaddingValues,
            ) {
                item(
                    key = "language-filter",
                    contentType = "language-filter",
                ) {
                    SourceLanguageFilterChips(
                        languages = state.languages,
                        selectedLanguage = state.selectedLanguage,
                        onLanguageFilterChange = onLanguageFilterChange,
                        modifier = Modifier.animateItem(),
                    )
                }
                items(
                    items = state.items,
                    contentType = { "item" },
                    key = { "source-${it.key()}" },
                ) { source ->
                    SourceItem(
                        modifier = Modifier.animateItem(),
                        source = source,
                        isObsolete = state.isObsoleteSource(source),
                        onClickItem = onClickItem,
                        onLongClickItem = onLongClickItem,
                        onClickPin = onClickPin,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceLanguageFilterChips(
    languages: List<String>,
    selectedLanguage: String?,
    onLanguageFilterChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            languages.forEach { language ->
                SourceLanguageFilterChip(
                    label = LocaleHelper.getSourceDisplayName(language, context),
                    selected = language == selectedLanguage,
                    onClick = {
                        if (language != selectedLanguage) {
                            onLanguageFilterChange(language)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceLanguageFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            contentPadding = PaddingValues(horizontal = 10.dp),
        )
    }
}

@Composable
private fun SourceItem(
    source: Source,
    isObsolete: Boolean,
    onClickItem: (Source, Listing) -> Unit,
    onLongClickItem: (Source) -> Unit,
    onClickPin: (Source) -> Unit,
    modifier: Modifier = Modifier,
) {
    BaseSourceItem(
        modifier = modifier,
        source = source,
        onClickItem = {
            onClickItem(
                source,
                if (source.supportsLatest) Listing.Latest else Listing.Popular,
            )
        },
        onLongClickItem = { onLongClickItem(source) },
        action = {
            SourcePinButton(
                isPinned = Pin.Pinned in source.pin,
                onClick = { onClickPin(source) },
            )
        },
        content = { _, sourceLangString ->
            SourceItemContent(
                source = source,
                sourceLangString = sourceLangString,
                isObsolete = isObsolete,
            )
        },
    )
}

@Composable
private fun RowScope.SourceItemContent(
    source: Source,
    sourceLangString: String?,
    isObsolete: Boolean,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = MaterialTheme.padding.medium)
            .weight(1f),
    ) {
        Text(
            text = source.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (sourceLangString != null || isObsolete) {
            FlowRow(
                modifier = Modifier.secondaryItemAlpha(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                ProvideTextStyle(value = MaterialTheme.typography.bodySmall) {
                    if (sourceLangString != null) {
                        Text(
                            text = sourceLangString,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isObsolete) {
                        if (sourceLangString != null) DotSeparatorNoSpaceText()
                        Text(
                            text = stringResource(MR.strings.ext_obsolete).uppercase(),
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePinButton(
    isPinned: Boolean,
    onClick: () -> Unit,
) {
    val icon = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin
    val tint = if (isPinned) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onBackground.copy(
            alpha = SECONDARY_ALPHA,
        )
    }
    val description = if (isPinned) MR.strings.action_unpin else MR.strings.action_pin
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            tint = tint,
            contentDescription = stringResource(description),
        )
    }
}

@Composable
fun SourceOptionsDialog(
    source: Source,
    isSensitive: Boolean?,
    onClickPin: () -> Unit,
    onClickExtensionInfo: (() -> Unit)?,
    onClickDisable: () -> Unit,
    onClickSensitive: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = source.visualName)
        },
        text = {
            Column {
                val textId = if (Pin.Pinned in source.pin) MR.strings.action_unpin else MR.strings.action_pin
                Text(
                    text = stringResource(textId),
                    modifier = Modifier
                        .clickable(onClick = onClickPin)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
                if (onClickExtensionInfo != null) {
                    Text(
                        text = stringResource(MR.strings.label_extension_info),
                        modifier = Modifier
                            .clickable(onClick = onClickExtensionInfo)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
                if (!source.isLocal()) {
                    if (isSensitive != null) {
                        Text(
                            text = stringResource(
                                if (isSensitive) {
                                    MR.strings.ext_mark_not_sensitive
                                } else {
                                    MR.strings.ext_set_as_sensitive
                                },
                            ),
                            modifier = Modifier
                                .clickable { onClickSensitive(!isSensitive) }
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        )
                    }
                    Text(
                        text = stringResource(MR.strings.action_disable),
                        modifier = Modifier
                            .clickable(onClick = onClickDisable)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
    )
}
