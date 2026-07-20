package eu.kanade.tachiyomi.network

import android.app.NotificationManager
import android.content.Context
import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.interceptor.IgnoreGzipInterceptor
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

class NetworkHelperTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `default clients do not expose IgnoreGzipInterceptor`() {
        mockkStatic(CookieManager::class)
        every { CookieManager.getInstance() } returns mockk(relaxed = true)

        val networkHelper = NetworkHelper(
            context = context(),
            preferences = NetworkPreferences(InMemoryPreferenceStore()),
        )

        networkHelper.client.networkInterceptors.map { it::class } shouldNotContain IgnoreGzipInterceptor::class
        networkHelper.nonCloudflareClient.networkInterceptors.map { it::class } shouldNotContain
            IgnoreGzipInterceptor::class
    }

    private fun context(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns tempDir
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns
            mockk<NotificationManager>(relaxed = true)
        return context
    }
}
