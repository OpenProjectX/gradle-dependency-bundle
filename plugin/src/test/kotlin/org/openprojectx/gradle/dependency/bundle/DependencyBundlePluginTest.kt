package org.openprojectx.gradle.dependency.bundle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

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
}
