package org.openprojectx.gradle.dependency.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.openprojectx.gradle.dependency.bundle.core.ManifestJson
import org.openprojectx.gradle.dependency.bundle.core.ReportRenderer
import org.openprojectx.gradle.dependency.bundle.core.RepositoryAuditor

abstract class AuditArtifactRepositoryTask : DefaultTask() {
    @get:InputFile abstract val manifestFile: RegularFileProperty
    @get:Input abstract val repositoryUrl: Property<String>
    @get:Input @get:Optional abstract val username: Property<String>
    @get:Input @get:Optional abstract val password: Property<String>
    @get:OutputDirectory abstract val reportDirectory: DirectoryProperty

    @TaskAction
    fun audit() {
        val manifest = ManifestJson.read(manifestFile.get().asFile.toPath())
        val report = RepositoryAuditor(
            repositoryUrl.get(),
            username.orNull,
            password.orNull,
        ).audit(manifest)
        val directory = reportDirectory.get().asFile.toPath()
        ManifestJson.writeAudit(report, directory.resolve("report.json"))
        directory.resolve("report.txt").toFile().writeText(ReportRenderer.render(manifest, report))
        logger.lifecycle("Audited {} artifacts: {} available, {} missing, {} errors", report.checked, report.available, report.missing, report.errors)
        if (report.missing > 0 || report.errors > 0) {
            throw GradleException("Repository audit failed; see ${directory.resolve("report.txt")}")
        }
    }
}
