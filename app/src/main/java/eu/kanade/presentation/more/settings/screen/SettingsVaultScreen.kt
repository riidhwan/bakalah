package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.vault.formatBytes
import eu.kanade.tachiyomi.data.vault.setup.ContentVaultConnectionTestResult
import eu.kanade.tachiyomi.data.vault.setup.ContentVaultSetupResult
import eu.kanade.tachiyomi.data.vault.setup.ContentVaultSetupService
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.domain.vault.model.WebDavVaultConfig
import tachiyomi.domain.vault.service.ContentVaultPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as contextStringResource

object SettingsVaultScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.vault_settings_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val preferences = remember { Injekt.get<ContentVaultPreferences>() }
        val setupService = remember { Injekt.get<ContentVaultSetupService>() }

        return listOf(
            getContentVaultGroup(
                preferences = preferences,
                setupService = setupService,
            ),
        )
    }

    @Composable
    private fun getContentVaultGroup(
        preferences: ContentVaultPreferences,
        setupService: ContentVaultSetupService,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var showInitializeDialog by remember { mutableStateOf(false) }
        var processing by remember { mutableStateOf(false) }
        val configuredIdentity by preferences.configuredVaultIdentity.collectAsState()

        fun currentConfig() = WebDavVaultConfig(
            serverUrl = preferences.webDavServerUrl.get(),
            username = preferences.webDavUsername.get(),
            password = preferences.webDavPassword.get(),
            rootPath = preferences.webDavRootPath.get(),
        )

        fun showResult(result: ContentVaultSetupResult) {
            when (result) {
                ContentVaultSetupResult.ConnectionFailed -> context.toast(
                    MR.strings.vault_settings_error_connection_failed,
                )
                ContentVaultSetupResult.EmptyRoot -> showInitializeDialog = true
                ContentVaultSetupResult.IncompleteConfiguration -> context.toast(
                    MR.strings.vault_settings_error_incomplete_configuration,
                )
                ContentVaultSetupResult.InvalidManifest -> context.toast(
                    MR.strings.vault_settings_error_invalid_manifest,
                )
                is ContentVaultSetupResult.IdentityChanged -> {
                    context.toast(MR.strings.vault_settings_error_identity_changed)
                }
                ContentVaultSetupResult.NonVaultRoot -> context.toast(MR.strings.vault_settings_error_non_vault_root)
                is ContentVaultSetupResult.Connected -> {
                    context.toast(
                        context.contextStringResource(MR.strings.vault_settings_connected, result.displayName),
                    )
                }
                is ContentVaultSetupResult.Initialized -> {
                    context.toast(
                        context.contextStringResource(MR.strings.vault_settings_initialized, result.displayName),
                    )
                }
            }
        }

        fun validate(initializeEmptyRoot: Boolean) {
            scope.launch {
                processing = true
                val result = setupService.validate(currentConfig(), initializeEmptyRoot)
                processing = false
                showResult(result)
            }
        }

        if (showInitializeDialog) {
            AlertDialog(
                onDismissRequest = { showInitializeDialog = false },
                title = { Text(stringResource(MR.strings.vault_settings_initialize_title)) },
                text = {
                    Text(stringResource(MR.strings.vault_settings_initialize_message))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showInitializeDialog = false
                            validate(initializeEmptyRoot = true)
                        },
                    ) {
                        Text(stringResource(MR.strings.vault_settings_action_initialize))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showInitializeDialog = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.vault_settings_group_content_vault),
            preferenceItems = listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = preferences.webDavServerUrl,
                    title = stringResource(MR.strings.vault_settings_webdav_server_url),
                    subtitle = "%s",
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = preferences.webDavRootPath,
                    title = stringResource(MR.strings.vault_settings_root_path),
                    subtitle = "%s",
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = preferences.webDavUsername,
                    title = stringResource(MR.strings.username),
                    subtitle = if (preferences.webDavUsername.get().isBlank()) {
                        ""
                    } else {
                        stringResource(MR.strings.vault_settings_value_set)
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = preferences.webDavPassword,
                    title = stringResource(MR.strings.password),
                    subtitle = if (preferences.webDavPassword.get().isBlank()) {
                        ""
                    } else {
                        stringResource(MR.strings.vault_settings_value_set)
                    },
                    isPassword = true,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = preferences.newVaultDisplayName,
                    title = stringResource(MR.strings.vault_settings_new_display_name),
                    subtitle = "%s",
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.vault_settings_configured_vault),
                    subtitle = configuredIdentity.ifBlank { stringResource(MR.strings.none) },
                    enabled = false,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.localCacheLimitBytes,
                    entries = mapOf(
                        CACHE_LIMIT_512_MB to "512 MB",
                        CACHE_LIMIT_1_GB to "1 GB",
                        CACHE_LIMIT_2_GB to "2 GB",
                        CACHE_LIMIT_5_GB to "5 GB",
                        CACHE_LIMIT_10_GB to "10 GB",
                    ),
                    title = stringResource(MR.strings.vault_settings_local_cache_limit),
                    subtitleProvider = { value, _ -> formatBytes(value) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.vault_settings_test_webdav_connection),
                    subtitle = stringResource(MR.strings.vault_settings_test_webdav_connection_summary),
                    enabled = !processing,
                    onClick = {
                        scope.launch {
                            processing = true
                            val result = setupService.testConnection(currentConfig())
                            processing = false
                            context.toast(result.toToastMessage(context))
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.vault_settings_validate_root),
                    subtitle = stringResource(MR.strings.vault_settings_validate_root_summary),
                    enabled = !processing,
                    onClick = { validate(initializeEmptyRoot = false) },
                ),
            ),
        )
    }

    private fun ContentVaultConnectionTestResult.toToastMessage(context: Context): String {
        return when (this) {
            ContentVaultConnectionTestResult.Connected -> {
                context.contextStringResource(MR.strings.vault_settings_connection_succeeded)
            }
            ContentVaultConnectionTestResult.IncompleteConfiguration -> {
                context.contextStringResource(MR.strings.vault_settings_connection_incomplete_configuration)
            }
            is ContentVaultConnectionTestResult.Unauthorized -> {
                if (statusCode == HTTP_UNAUTHORIZED) {
                    context.contextStringResource(MR.strings.vault_settings_connection_unauthorized_401)
                } else {
                    context.contextStringResource(MR.strings.vault_settings_connection_forbidden_403)
                }
            }
            is ContentVaultConnectionTestResult.Failed -> {
                if (statusCode != null) {
                    context.contextStringResource(MR.strings.vault_settings_connection_failed_http, statusCode)
                } else if (detail != null) {
                    context.contextStringResource(MR.strings.vault_settings_connection_failed_detail, detail)
                } else {
                    context.contextStringResource(MR.strings.vault_settings_connection_failed_no_response)
                }
            }
        }
    }
}

private const val HTTP_UNAUTHORIZED = 401
private const val CACHE_LIMIT_512_MB = 512L * 1024L * 1024L
private const val CACHE_LIMIT_1_GB = 1024L * 1024L * 1024L
private const val CACHE_LIMIT_2_GB = 2L * 1024L * 1024L * 1024L
private const val CACHE_LIMIT_5_GB = 5L * 1024L * 1024L * 1024L
private const val CACHE_LIMIT_10_GB = 10L * 1024L * 1024L * 1024L
