package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import mihon.core.archive.archiveReader

internal class VaultPageLoader(
    context: Context,
    file: UniFile,
) : PageLoader() {

    private val delegate = ArchivePageLoader(file.archiveReader(context))

    override var isLocal: Boolean = true

    override suspend fun getPages() = delegate.getPages()

    override suspend fun loadPage(page: eu.kanade.tachiyomi.ui.reader.model.ReaderPage) {
        delegate.loadPage(page)
    }

    override fun recycle() {
        super.recycle()
        delegate.recycle()
    }
}
