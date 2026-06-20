package eu.kanade.tachiyomi.ui.vault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.vault.ImportRequestsScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object ImportRequestsTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 3u,
                title = stringResource(MR.strings.label_import_requests),
                icon = rememberVectorPainter(Icons.Outlined.TaskAlt),
            )
        }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { ImportRequestsScreenModel() }
        val state by screenModel.state.collectAsState()

        ImportRequestsScreen(
            state = state,
            onClickRequest = { navigator.push(ImportRequestChaptersScreen(it)) },
            onIncludeSensitiveChange = screenModel::setIncludeSensitiveContent,
        )
    }
}
