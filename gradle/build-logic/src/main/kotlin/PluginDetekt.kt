import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import mihon.gradle.extensions.alias
import mihon.gradle.extensions.libs
import mihon.gradle.extensions.plugins
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

@Suppress("UNUSED")
class PluginDetekt : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.detekt)
        }

        detekt {
            toolVersion = libs.versions.detekt.get()
            buildUponDefaultConfig = true
            allRules = false
            ignoreFailures = true
            parallel = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            baseline = rootProject.file("config/detekt/baseline.xml")
            source.setFrom(rootProject.layout.projectDirectory.asFile)
            basePath = rootProject.layout.projectDirectory.asFile.absolutePath
        }

        tasks.withType<Detekt>().configureEach {
            include("**/*.kt", "**/*.kts")
            excludeDetektIgnoredPaths()
        }

        tasks.withType<DetektCreateBaselineTask>().configureEach {
            include("**/*.kt", "**/*.kts")
            excludeDetektIgnoredPaths()
        }
    }
}

private fun Project.detekt(block: DetektExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Detekt.excludeDetektIgnoredPaths() {
    exclude(
        "**/.agent-tmp/**",
        "**/.gradle/**",
        "**/.idea/**",
        "**/.kotlin/**",
        "**/build/**",
    )
}

private fun DetektCreateBaselineTask.excludeDetektIgnoredPaths() {
    exclude(
        "**/.agent-tmp/**",
        "**/.gradle/**",
        "**/.idea/**",
        "**/.kotlin/**",
        "**/build/**",
    )
}
