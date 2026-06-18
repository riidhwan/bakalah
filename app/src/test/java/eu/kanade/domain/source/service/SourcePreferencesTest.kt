package eu.kanade.domain.source.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference

class SourcePreferencesTest {

    @Test
    fun `sensitive extension packages are stored behind private preference key`() {
        val preferences = SourcePreferences(InMemoryPreferenceStore())

        Preference.isPrivate(preferences.sensitiveExtensions.key()) shouldBe true
        Preference.isAppState(preferences.includeSensitiveExtensions.key()) shouldBe true
    }

    @Test
    fun `marking an extension adds package name`() {
        val preferences = SourcePreferences(InMemoryPreferenceStore())

        preferences.markExtensionSensitive("pkg.test")

        preferences.sensitiveExtensions.get() shouldBe setOf("pkg.test")
    }

    @Test
    fun `unmarking an extension removes package name`() {
        val preferences = SourcePreferences(InMemoryPreferenceStore())
        preferences.markExtensionSensitive("pkg.test")

        preferences.unmarkExtensionSensitive("pkg.test")

        preferences.sensitiveExtensions.get() shouldBe emptySet()
    }

    @Test
    fun `uninstall cleanup removes package name`() {
        val preferences = SourcePreferences(InMemoryPreferenceStore())
        preferences.markExtensionSensitive("pkg.test")

        preferences.removeSensitiveExtension("pkg.test")

        preferences.sensitiveExtensions.get() shouldBe emptySet()
    }
}
