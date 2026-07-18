package org.openprojectx.gradle.dependency.bundle.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ManifestJsonTest {
    @Test
    fun `round trips graph manifest`(@TempDir directory: Path) {
        val manifest = DependencyBundleManifest(
            producer = Producer("test", "9.6.1"),
            configurations = listOf(ConfigurationGraph("runtimeClasspath", listOf("a:b:1"))),
            components = mapOf("a:b:1" to ComponentNode("a:b:1", "a", "b", "1")),
            edges = emptyList(),
            artifacts = listOf(ArtifactRecord("a:b:1", "a/b/1/b-1.jar", "jar", 1, "00")),
        )
        val file = directory.resolve("manifest.json")
        ManifestJson.write(manifest, file)
        assertEquals(manifest.copy(generatedAt = ManifestJson.read(file).generatedAt), ManifestJson.read(file))
    }
}
