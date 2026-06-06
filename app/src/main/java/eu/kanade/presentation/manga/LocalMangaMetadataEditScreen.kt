package eu.kanade.presentation.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.manga.LocalMangaMetadataEditScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun LocalMangaMetadataEditScreen(
    state: LocalMangaMetadataEditScreenModel.State,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onTitleChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onGenresChange: (String) -> Unit,
    onStatusChange: (Int) -> Unit,
    onSave: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val onSaveClick = {
        keyboardController?.hide()
        focusManager.clearFocus()
        onSave()
    }

    Scaffold(
        topBar = { topBarScrollBehavior ->
            AppBar(
                titleContent = {
                    AppBarTitle(
                        title = stringResource(MR.strings.local_manga_metadata_edit_title),
                        subtitle = state.mangaTitle,
                    )
                },
                navigateUp = navigateUp,
                actions = {
                    TextButton(
                        enabled = !state.isSaving,
                        onClick = onSaveClick,
                    ) {
                        Text(text = stringResource(MR.strings.action_save))
                    }
                },
                scrollBehavior = topBarScrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetadataTextField(
                value = state.title,
                onValueChange = onTitleChange,
                label = stringResource(MR.strings.title),
                isError = state.titleError != null,
                supportingText = state.titleError?.let { stringResource(it) },
                singleLine = true,
            )
            MetadataTextField(
                value = state.author,
                onValueChange = onAuthorChange,
                label = stringResource(MR.strings.author),
                singleLine = true,
            )
            MetadataTextField(
                value = state.artist,
                onValueChange = onArtistChange,
                label = stringResource(MR.strings.artist),
                singleLine = true,
            )
            MetadataTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = stringResource(MR.strings.description),
                minLines = 4,
            )
            MetadataTextField(
                value = state.genres,
                onValueChange = onGenresChange,
                label = stringResource(MR.strings.local_manga_metadata_genres),
                singleLine = true,
            )
            StatusDropdown(
                status = state.status,
                onStatusChange = onStatusChange,
            )
        }
    }
}

@Composable
private fun MetadataTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusDropdown(
    status: Int,
    onStatusChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember { LocalMangaMetadataStatusOption.entries }
    val selected = options.firstOrNull { it.status == status } ?: LocalMangaMetadataStatusOption.Unknown

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(selected.label),
            onValueChange = {},
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            readOnly = true,
            label = { Text(stringResource(MR.strings.status)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = if (option == selected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onStatusChange(option.status)
                        expanded = false
                    },
                )
            }
        }
    }
}

private enum class LocalMangaMetadataStatusOption(
    val status: Int,
    val label: StringResource,
) {
    Unknown(SManga.UNKNOWN, MR.strings.unknown),
    Ongoing(SManga.ONGOING, MR.strings.ongoing),
    Completed(SManga.COMPLETED, MR.strings.completed),
    Licensed(SManga.LICENSED, MR.strings.licensed),
    PublishingFinished(SManga.PUBLISHING_FINISHED, MR.strings.publishing_finished),
    Cancelled(SManga.CANCELLED, MR.strings.cancelled),
    OnHiatus(SManga.ON_HIATUS, MR.strings.on_hiatus),
}
