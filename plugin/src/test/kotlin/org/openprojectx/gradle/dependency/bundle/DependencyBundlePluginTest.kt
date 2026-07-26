package org.openprojectx.gradle.dependency.bundle

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream

class DependencyBundlePluginTest {
    @Test
    fun `registers export and audit tasks`() {
        val project = ProjectBuilder.builder().build()

        project.pluginManager.apply(DependencyBundlePlugin::class.java)

        assertNotNull(project.extensions.findByName("dependencyBundle"))
        assertNotNull(project.tasks.findByName("exportDependencyBundle"))
        assertNotNull(project.tasks.findByName("dependencyBundleReport"))
        assertNotNull(project.tasks.findByName("auditArtifactRepository"))
    }

    @Test
    fun `can be applied to a subproject`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()

        app.pluginManager.apply(DependencyBundlePlugin::class.java)

        assertNotNull(app.extensions.findByName("dependencyBundle"))
        assertNotNull(app.tasks.findByName("exportDependencyBundle"))
        assertNotNull(app.tasks.findByName("dependencyBundleReport"))
        assertNotNull(app.tasks.findByName("auditArtifactRepository"))
    }

    @Test
    fun `subproject export copies only resolved module cache entries`(@TempDir directory: Path) {
        val repository = directory.resolve("repository")
        publishEmptyJar(repository, "test", "kept", "1")
        publishEmptyJar(repository, "test", "other", "1")

        Files.writeString(
            directory.resolve("settings.gradle"),
            """
            rootProject.name = 'consumer'
            include 'app', 'other'
            """.trimIndent(),
        )
        val app = Files.createDirectories(directory.resolve("app"))
        Files.writeString(
            app.resolve("build.gradle"),
            """
            plugins {
                id 'java'
                id 'org.openprojectx.gradle.dependency.bundle'
            }
            repositories {
                maven { url = uri('../repository') }
            }
            dependencies {
                implementation 'test:kept:1'
            }
            dependencyBundle {
                includeBuildDependencies.set(false)
                includeSources.set(false)
            }
            tasks.register('recordGradleHome') {
                doLast {
                    def output = file("${'$'}buildDir/gradle-home.txt")
                    output.parentFile.mkdirs()
                    output.text = gradle.gradleUserHomeDir.absolutePath
                }
            }
            """.trimIndent(),
        )
        val other = Files.createDirectories(directory.resolve("other"))
        Files.writeString(
            other.resolve("build.gradle"),
            """
            plugins {
                id 'java'
            }
            repositories {
                maven { url = uri('../repository') }
            }
            dependencies {
                implementation 'test:other:1'
            }
            """.trimIndent(),
        )

        val testKitDirectory = Files.createDirectories(directory.resolve("test-kit"))
        val runner = GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withTestKitDir(testKitDirectory.toFile())
            .withPluginClasspath()

        runner.withArguments(":app:recordGradleHome").build()
        val gradleHome = Path.of(Files.readString(app.resolve("build/gradle-home.txt")))
        val moduleCache = gradleHome.resolve("caches/modules-2/files-2.1")
        val selected = moduleCache.resolve("test/kept/1/content-hash/kept-1.jar")
        Files.createDirectories(selected.parent)
        Files.copy(repository.resolve("test/kept/1/kept-1.jar"), selected)
        val otherProject = moduleCache.resolve("test/other/1/content-hash/other-1.jar")
        Files.createDirectories(otherProject.parent)
        Files.copy(repository.resolve("test/other/1/other-1.jar"), otherProject)
        val unrelated = moduleCache.resolve("test/unrelated/1/content-hash/unrelated-1.jar")
        Files.createDirectories(unrelated.parent)
        Files.writeString(unrelated, "must not be bundled")

        runner.withArguments("--stacktrace", ":app:exportDependencyBundle").build()

        val bundleRepository = app.resolve("build/dependency-bundle/m2/repository")
        assertTrue(Files.isRegularFile(bundleRepository.resolve("test/kept/1/kept-1.jar")))
        assertFalse(Files.exists(bundleRepository.resolve("test/other")))
        assertFalse(Files.exists(bundleRepository.resolve("test/unrelated")))
        assertFalse(Files.exists(directory.resolve("build/dependency-bundle")))
    }

    private fun publishEmptyJar(repository: Path, group: String, module: String, version: String) {
        val directory = repository.resolve("${group.replace('.', '/')}/$module/$version")
        Files.createDirectories(directory)
        JarOutputStream(Files.newOutputStream(directory.resolve("$module-$version.jar"))).use { }
        Files.writeString(
            directory.resolve("$module-$version.pom"),
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$module</artifactId>
              <version>$version</version>
            </project>
            """.trimIndent(),
        )
    }
}
