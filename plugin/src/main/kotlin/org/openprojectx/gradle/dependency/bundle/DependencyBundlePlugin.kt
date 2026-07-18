package org.openprojectx.gradle.dependency.bundle

import org.gradle.api.Plugin
import org.gradle.api.Project

class DependencyBundlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "Dependency bundle plugin must be applied to the root project" }
        val extension = project.extensions.create("dependencyBundle", DependencyBundleExtension::class.java)
        extension.outputDirectory.convention(project.layout.buildDirectory.dir("dependency-bundle"))

        val export = project.tasks.register("exportDependencyBundle", ExportDependencyBundleTask::class.java) { task ->
            task.group = "dependency bundle"
            task.description = "Captures dependency graphs and exports a complete Maven-layout repository."
            task.configurationNames.set(extension.configurations)
            task.includeBuildDependencies.set(extension.includeBuildDependencies)
            task.includeSources.set(extension.includeSources)
            task.additionalModules.set(extension.additionalModules)
            task.gradleVariantRequests.set(extension.gradleVariantRequests)
            task.artifactRepositoryUrls.set(extension.artifactRepositoryUrls)
            task.outputDirectory.set(extension.outputDirectory)
            task.pluginVersion.set(project.provider { project.version.toString() })
            task.notCompatibleWithConfigurationCache("Resolves dependency graphs across all projects and buildscript classpaths")
        }

        project.tasks.register("dependencyBundleReport", DependencyBundleReportTask::class.java) { task ->
            task.group = "dependency bundle"
            task.description = "Renders the serialized graph as a Gradle dependencies-style text report."
            task.dependsOn(export)
            task.manifestFile.convention(export.flatMap { it.outputDirectory.file("dependency-graph.json") })
            task.reportFile.convention(export.flatMap { it.outputDirectory.file("dependency-graph.txt") })
        }

        project.tasks.register("auditArtifactRepository", AuditArtifactRepositoryTask::class.java) { task ->
            task.group = "dependency bundle"
            task.description = "Audits every serialized artifact against a restricted Maven/JFrog repository."
            task.manifestFile.convention(export.flatMap { it.outputDirectory.file("dependency-graph.json") })
            task.repositoryUrl.convention(project.providers.gradleProperty("artifactRepositoryUrl"))
            task.username.convention(project.providers.environmentVariable("JFROG_USERNAME"))
            task.password.convention(project.providers.environmentVariable("JFROG_PASSWORD"))
            task.reportDirectory.convention(project.layout.buildDirectory.dir("reports/dependency-audit"))
        }
    }
}
