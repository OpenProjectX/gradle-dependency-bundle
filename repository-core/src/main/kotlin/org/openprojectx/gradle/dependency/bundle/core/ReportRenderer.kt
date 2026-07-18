package org.openprojectx.gradle.dependency.bundle.core

object ReportRenderer {
    fun renderGraph(manifest: DependencyBundleManifest): String = buildString {
        appendLine("Dependency bundle graph")
        appendLine("Gradle ${manifest.producer.gradleVersion}; ${manifest.components.size} components; ${manifest.artifacts.size} artifacts")
        appendLine()
        appendGraphs(manifest, emptyMap())
    }

    fun render(manifest: DependencyBundleManifest, report: AuditReport): String = buildString {
        appendLine("Repository: ${report.repository}")
        appendLine("Checked: ${report.checked}, available: ${report.available}, missing: ${report.missing}, errors: ${report.errors}")
        appendLine()

        val statusByPath = report.results.associateBy { it.path }
        appendGraphs(manifest, statusByPath)

        val missing = report.results.filter { it.status != "available" }
        if (missing.isNotEmpty()) {
            appendLine("Missing or inaccessible artifacts:")
            missing.forEach { appendLine("  [${it.status.uppercase()}] ${it.path}${it.httpStatus?.let { code -> " (HTTP $code)" } ?: ""}") }
        }
    }

    private fun StringBuilder.appendGraphs(
        manifest: DependencyBundleManifest,
        statuses: Map<String, ArtifactAuditResult>,
    ) {
        manifest.configurations.forEach { configuration ->
            appendLine(configuration.name)
            configuration.roots.forEach { root ->
                renderNode(manifest, root, configuration.name, "\\--- ", "     ", mutableSetOf(), statuses)
            }
            appendLine()
        }
    }

    private fun StringBuilder.renderNode(
        manifest: DependencyBundleManifest,
        component: String,
        configuration: String,
        branch: String,
        indent: String,
        visited: MutableSet<String>,
        statuses: Map<String, ArtifactAuditResult>,
    ) {
        val firstVisit = visited.add(component)
        appendLine("$branch$component${if (!firstVisit) " (*)" else ""}")
        if (!firstVisit) return
        manifest.components[component]?.selectionReason?.takeIf { it.isNotBlank() }?.let {
            appendLine("$indent(reason: $it)")
        }
        manifest.artifacts.filter { it.component == component }.forEach { artifact ->
            val status = statuses[artifact.path]?.status?.uppercase()
            val label = status?.let { "[$it] " } ?: ""
            appendLine("$indent+--- $label${artifact.path.substringAfterLast('/')}")
        }
        val children = manifest.edges.filter { it.configuration == configuration && it.from == component }
        children.forEachIndexed { index, edge ->
            val last = index == children.lastIndex
            renderNode(
                manifest,
                edge.to,
                configuration,
                "$indent${if (last) "\\--- " else "+--- "}",
                "$indent${if (last) "     " else "|    "}",
                visited,
                statuses,
            )
        }
    }
}
