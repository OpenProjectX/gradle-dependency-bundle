package org.openprojectx.gradle.dependency.bundle.core

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import java.time.Instant

data class DependencyBundleManifest(
    val formatVersion: String = "1.0",
    val generatedAt: String = Instant.now().toString(),
    val producer: Producer,
    val configurations: List<ConfigurationGraph>,
    val components: Map<String, ComponentNode>,
    val edges: List<DependencyEdge>,
    val artifacts: List<ArtifactRecord>,
)

data class Producer(
    val pluginVersion: String,
    val gradleVersion: String,
)

data class ConfigurationGraph(
    val name: String,
    val roots: List<String>,
)

data class ComponentNode(
    val id: String,
    val group: String? = null,
    val module: String? = null,
    val version: String? = null,
    val selectionReason: String? = null,
    val variants: List<VariantRecord> = emptyList(),
)

data class VariantRecord(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class DependencyEdge(
    val configuration: String,
    val from: String,
    val to: String,
    val requested: String,
    val constraint: Boolean = false,
)

data class ArtifactRecord(
    val component: String? = null,
    val path: String,
    val kind: String,
    val size: Long,
    val sha256: String,
)

data class AuditReport(
    val repository: String,
    val generatedAt: String = Instant.now().toString(),
    val checked: Int,
    val available: Int,
    val missing: Int,
    val errors: Int,
    val results: List<ArtifactAuditResult>,
)

data class ArtifactAuditResult(
    val path: String,
    val status: String,
    val httpStatus: Int? = null,
    val message: String? = null,
)

object ManifestJson {
    private val mapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun write(manifest: DependencyBundleManifest, path: Path) {
        path.parent?.toFile()?.mkdirs()
        mapper.writeValue(path.toFile(), manifest)
    }

    fun read(path: Path): DependencyBundleManifest = mapper.readValue(path.toFile())

    fun writeAudit(report: AuditReport, path: Path) {
        path.parent?.toFile()?.mkdirs()
        mapper.writeValue(path.toFile(), report)
    }
}
