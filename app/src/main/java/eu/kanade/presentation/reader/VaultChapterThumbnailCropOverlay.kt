package eu.kanade.presentation.reader

import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import kotlin.math.min

@Composable
fun VaultChapterThumbnailCropOverlay(
    page: ReaderPage,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageFile by produceState<File?>(initialValue = null, key1 = page) {
        value = withContext(Dispatchers.IO) {
            val stream = page.stream ?: return@withContext null
            val dir = File(context.cacheDir, "vault-chapter-thumbnail-crop").apply {
                mkdirs()
                listFiles()?.forEach(File::delete)
            }
            File.createTempFile("page-${page.index}-", ".image", dir).also { file ->
                stream().use { input ->
                    file.outputStream().use(input::copyTo)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val cropSize = min(maxWidth.value - 48f, maxHeight.value).dp * 0.78f
            val horizontalScrimWidth = (maxWidth - cropSize) / 2
            val verticalScrimHeight = (maxHeight - cropSize) / 2
            val scrimColor = Color.Black.copy(alpha = 0.55f)

            if (imageFile == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                AndroidView(
                    factory = { viewContext ->
                        SubsamplingScaleImageView(viewContext).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            setMinimumDpi(1)
                            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP)
                            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_OUTSIDE)
                            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
                            setImage(ImageSource.uri(viewContext, Uri.fromFile(imageFile)))
                        }
                    },
                    update = { view ->
                        if (!view.hasImage()) {
                            view.setImage(ImageSource.uri(context, Uri.fromFile(imageFile)))
                        }
                    },
                    onRelease = SubsamplingScaleImageView::recycle,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(verticalScrimHeight)
                    .background(scrimColor),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(verticalScrimHeight)
                    .background(scrimColor),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(horizontalScrimWidth)
                    .height(cropSize)
                    .background(scrimColor),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(horizontalScrimWidth)
                    .height(cropSize)
                    .background(scrimColor),
            )
            Box(
                modifier = Modifier
                    .size(cropSize)
                    .border(2.dp, Color.White),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_cancel),
                    tint = Color.White,
                )
            }
            Text(
                text = stringResource(MR.strings.vault_chapter_thumbnail),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onConfirm,
                enabled = imageFile != null,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = stringResource(MR.strings.action_done),
                    tint = if (imageFile != null) Color.White else Color.White.copy(alpha = 0.38f),
                )
            }
        }
    }
}
