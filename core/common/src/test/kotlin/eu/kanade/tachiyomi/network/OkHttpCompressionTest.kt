package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class OkHttpCompressionTest {

    @Test
    fun `zstd interceptor is available to extensions`() {
        Class.forName("okhttp3.zstd.Zstd") shouldNotBe null
    }
}
