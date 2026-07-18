package org.openprojectx.gradle.dependency.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.openprojectx.gradle.dependency.bundle.core.ManifestJson
import org.openprojectx.gradle.dependency.bundle.core.ReportRenderer

abstract class DependencyBundleReportTask : DefaultTask() {
    @get:InputFile abstract val manifestFile: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun render() {
        val manifest = ManifestJson.read(manifestFile.get().asFile.toPath())
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(ReportRenderer.renderGraph(manifest))
        logger.lifecycle("Dependency graph report: {}", output)
    }
}
