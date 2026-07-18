package org.openprojectx.gradle.dependency.bundle.cli

import org.openprojectx.gradle.dependency.bundle.core.ManifestJson
import org.openprojectx.gradle.dependency.bundle.core.ReportRenderer
import org.openprojectx.gradle.dependency.bundle.core.RepositoryAuditor
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    val options = parseOptions(arguments.toList())
    val manifestPath = Path.of(options["manifest"] ?: "/dependency-bundle/dependency-graph.json")
    val repositoryUrl = options["repository"]
        ?: System.getenv("JFROG_URL")
        ?: usage("Missing --repository or JFROG_URL")
    val output = Path.of(options["output"] ?: "/tmp/dependency-audit.json")
    val textOutput = Path.of(options["text-output"] ?: "/tmp/dependency-audit.txt")

    val manifest = ManifestJson.read(manifestPath)
    val report = RepositoryAuditor(
        repositoryUrl = repositoryUrl,
        username = options["username"] ?: System.getenv("JFROG_USERNAME"),
        password = options["password"] ?: System.getenv("JFROG_PASSWORD"),
        parallelism = options["parallelism"]?.toIntOrNull() ?: 8,
    ).audit(manifest)
    val rendered = ReportRenderer.render(manifest, report)
    ManifestJson.writeAudit(report, output)
    textOutput.parent?.toFile()?.mkdirs()
    textOutput.writeText(rendered)
    print(rendered)
    if (report.missing > 0 || report.errors > 0) exitProcess(1)
}

private fun parseOptions(arguments: List<String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        if (!argument.startsWith("--")) usage("Unexpected argument: $argument")
        if (index + 1 >= arguments.size) usage("Missing value for $argument")
        result[argument.removePrefix("--")] = arguments[index + 1]
        index += 2
    }
    return result
}

private fun usage(message: String): Nothing {
    System.err.println(message)
    System.err.println(
        "Usage: auditor --repository URL [--manifest FILE] [--username USER] [--password PASSWORD] " +
            "[--output FILE] [--text-output FILE] [--parallelism N]",
    )
    exitProcess(2)
}
