package eu.kanade.presentation.reader

import android.graphics.PointF
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailCrop
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import kotlin.math.max
import kotlin.math.min

@Composable
fun VaultChapterThumbnailCropOverlay(
    page: ReaderPage,
    isPublishing: Boolean,
    onConfirm: (VaultChapterThumbnailCrop) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var imageView by remember { mutableStateOf<SubsamplingScaleImageView?>(null) }
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
            val horizontalCropPaddingPx = with(density) { horizontalScrimWidth.toPx() }.toInt()
            val verticalCropPaddingPx = with(density) { verticalScrimHeight.toPx() }.toInt()
            val cropSizePx = with(density) { cropSize.toPx() }

            if (imageFile == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                AndroidView(
                    factory = { viewContext ->
                        SubsamplingScaleImageView(viewContext).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            setMinimumDpi(1)
                            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_OUTSIDE)
                            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
                            setCropFramePadding(horizontalCropPaddingPx, verticalCropPaddingPx)
                            setOnImageEventListener(
                                object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                                    override fun onReady() {
                                        setupVaultChapterThumbnailCropZoom(cropSizePx)
                                    }
                                },
                            )
                            setOnStateChangedListener(
                                object : SubsamplingScaleImageView.DefaultOnStateChangedListener() {
                                    override fun onScaleChanged(newScale: Float, origin: Int) {
                                        clampVaultChapterThumbnailCropToFrame(cropSizePx)
                                    }

                                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                                        clampVaultChapterThumbnailCropToFrame(cropSizePx)
                                    }
                                },
                            )
                            setImage(ImageSource.uri(viewContext, Uri.fromFile(imageFile)))
                        }
                    },
                    update = { view ->
                        imageView = view
                        view.setCropFramePadding(horizontalCropPaddingPx, verticalCropPaddingPx)
                        if (view.hasImage()) {
                            view.setVaultChapterThumbnailCropMinScale(cropSizePx)
                        }
                        if (!view.hasImage()) {
                            view.setImage(ImageSource.uri(context, Uri.fromFile(imageFile)))
                        }
                    },
                    onRelease = { view ->
                        if (imageView === view) imageView = null
                        view.recycle()
                    },
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
            IconButton(
                onClick = onCancel,
                enabled = !isPublishing,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_cancel),
                    tint = if (isPublishing) Color.White.copy(alpha = 0.38f) else Color.White,
                )
            }
            Text(
                text = stringResource(MR.strings.vault_chapter_thumbnail),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    imageView?.let { view ->
                        val horizontalInsetPx = with(density) { 48.dp.toPx() }
                        val cropSizePx = min(view.width - horizontalInsetPx, view.height.toFloat()) * 0.78f
                        view.toVaultChapterThumbnailCrop(cropSizePx)
                    }?.let(onConfirm)
                },
                enabled = imageFile != null && !isPublishing,
            ) {
                if (isPublishing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = stringResource(MR.strings.action_done),
                        tint = if (imageFile != null) Color.White else Color.White.copy(alpha = 0.38f),
                    )
                }
            }
        }
    }
}

private fun SubsamplingScaleImageView.setCropFramePadding(horizontalPx: Int, verticalPx: Int) {
    setPadding(horizontalPx, verticalPx, horizontalPx, verticalPx)
}

private fun SubsamplingScaleImageView.setupVaultChapterThumbnailCropZoom(cropSizePx: Float) {
    if (width <= 0 || height <= 0 || sWidth <= 0 || sHeight <= 0) return
    setVaultChapterThumbnailCropMinScale(cropSizePx)
    val initialScale = max(width.toFloat() / sWidth, height.toFloat() / sHeight)
    maxScale = initialScale * MAX_VAULT_CHAPTER_THUMBNAIL_ZOOM_SCALE
    setDoubleTapZoomScale(initialScale * 2)
    setScaleAndCenter(initialScale, PointF(sWidth / 2f, sHeight / 2f))
}

private fun SubsamplingScaleImageView.setVaultChapterThumbnailCropMinScale(cropSizePx: Float) {
    if (!hasImage() || sWidth <= 0 || sHeight <= 0) return
    minScale = max(cropSizePx / sWidth, cropSizePx / sHeight)
}

private fun SubsamplingScaleImageView.clampVaultChapterThumbnailCropToFrame(cropSizePx: Float) {
    if (!hasImage() || width <= 0 || height <= 0 || scale <= 0f) return
    val center = viewToSourceCoord(width / 2f, height / 2f) ?: return
    val halfCropSource = cropSizePx / scale / 2f
    val clampedCenter = PointF(
        center.x.coerceCropCenter(halfCropSource, sWidth.toFloat()),
        center.y.coerceCropCenter(halfCropSource, sHeight.toFloat()),
    )
    if (clampedCenter != center) {
        setScaleAndCenter(scale, clampedCenter)
    }
}

private fun Float.coerceCropCenter(halfCropSource: Float, sourceSize: Float): Float {
    val minCenter = halfCropSource.coerceAtMost(sourceSize / 2f)
    val maxCenter = (sourceSize - halfCropSource).coerceAtLeast(sourceSize / 2f)
    return coerceIn(minCenter, maxCenter)
}

private fun SubsamplingScaleImageView.toVaultChapterThumbnailCrop(cropSizePx: Float): VaultChapterThumbnailCrop? {
    if (!hasImage() || width <= 0 || height <= 0) return null
    val left = (width - cropSizePx) / 2f
    val top = (height - cropSizePx) / 2f
    val topLeft = viewToSourceCoord(left, top) ?: return null
    val bottomRight = viewToSourceCoord(left + cropSizePx, top + cropSizePx) ?: return null
    if (topLeft.x < 0f || topLeft.y < 0f || bottomRight.x > sWidth || bottomRight.y > sHeight) {
        return null
    }
    val size = min(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y).toInt()
    if (size <= 0) return null
    return VaultChapterThumbnailCrop(
        left = topLeft.x.toInt().coerceIn(0, sWidth - 1),
        top = topLeft.y.toInt().coerceIn(0, sHeight - 1),
        size = size.coerceAtMost(sWidth).coerceAtMost(sHeight),
    )
}

private const val MAX_VAULT_CHAPTER_THUMBNAIL_ZOOM_SCALE = 5f
