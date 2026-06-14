package eu.kanade.tachiyomi.ui.reader

import android.graphics.Bitmap
import android.graphics.Rect
import eu.kanade.tachiyomi.data.vault.publishing.VaultChapterThumbnailCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min
import tachiyomi.decoder.ImageDecoder as TachiyomiImageDecoder

interface VaultChapterThumbnailImageNormalizer {
    suspend fun normalize(
        stream: () -> InputStream,
        crop: VaultChapterThumbnailCrop,
    ): ByteArray?
}

class DefaultVaultChapterThumbnailImageNormalizer : VaultChapterThumbnailImageNormalizer {

    override suspend fun normalize(
        stream: () -> InputStream,
        crop: VaultChapterThumbnailCrop,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val bitmap = stream().use { input ->
            val decoder = TachiyomiImageDecoder.newInstance(input) ?: return@use null
            try {
                decoder.decode()
            } finally {
                decoder.recycle()
            }
        } ?: return@withContext null

        try {
            val left = crop.left.coerceIn(0, bitmap.width - 1)
            val top = crop.top.coerceIn(0, bitmap.height - 1)
            val size = crop.size
                .coerceAtMost(bitmap.width - left)
                .coerceAtMost(bitmap.height - top)
                .coerceAtLeast(1)
            val sourceRect = Rect(
                left,
                top,
                (left + size).coerceIn(1, bitmap.width),
                (top + size).coerceIn(1, bitmap.height),
            )
            val cropSize = min(sourceRect.width(), sourceRect.height())
            val cropped = Bitmap.createBitmap(
                bitmap,
                sourceRect.left,
                sourceRect.top,
                cropSize,
                cropSize,
            )
            try {
                val scaled = Bitmap.createScaledBitmap(cropped, THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX, true)
                try {
                    ByteArrayOutputStream().use { output ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, output)
                        output.toByteArray()
                    }
                } finally {
                    if (scaled !== cropped) scaled.recycle()
                }
            } finally {
                if (cropped !== bitmap) cropped.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val THUMBNAIL_SIZE_PX = 256
        const val THUMBNAIL_JPEG_QUALITY = 90
    }
}
