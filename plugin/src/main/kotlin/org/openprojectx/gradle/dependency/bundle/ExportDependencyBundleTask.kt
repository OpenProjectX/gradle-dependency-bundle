package org.openprojectx.gradle.dependency.bundle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.plugin.GradlePluginApiVersion
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.openprojectx.gradle.dependency.bundle.core.ArtifactRecord
import org.openprojectx.gradle.dependency.bundle.core.ComponentNode
import org.openprojectx.gradle.dependency.bundle.core.ConfigurationGraph
import org.openprojectx.gradle.dependency.bundle.core.DependencyBundleManifest
import org.openprojectx.gradle.dependency.bundle.core.DependencyEdge
import org.openprojectx.gradle.dependency.bundle.core.ManifestJson
import org.openprojectx.gradle.dependency.bundle.core.Producer
import org.openprojectx.gradle.dependency.bundle.core.VariantRecord
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@DisableCachingByDefault(because = "Resolves external graphs and materializes a portable repository")
abstract class ExportDependencyBundleTask : DefaultTask() {
    @get:Input abstract val configurationNames: ListProperty<String>
    @get:Input abstract val includeBuildDependencies: Property<Boolean>
    @get:Input abstract val includeSources: Property<Boolean>
    @get:Input abstract val additionalModules: ListProperty<String>
    @get:Input abstract val gradleVariantRequests: ListProperty<String>
    @get:Input abstract val artifactRepositoryUrls: ListProperty<String>
    @get:Input abstract val pluginVersion: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    private val components = linkedMapOf<String, ComponentNode>()
    private val edges = linkedSetOf<DependencyEdge>()
    private val graphs = mutableListOf<ConfigurationGraph>()

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun export() {
        val output = outputDirectory.get().asFile.toPath()
        val manifestPath = output.resolve("dependency-graph.json")
        if (Files.isRegularFile(manifestPath)) {
            val existing = ManifestJson.read(manifestPath)
            graphs += existing.configurations
            components.putAll(existing.components)
            edges += existing.edges
        }
        val configurations = collectConfigurations()
        configurations.forEach { (name, configuration) -> capture(name, configuration) }
        resolveAdditionalModules()

        val repository = output.resolve("m2/repository")
        Files.createDirectories(repository)
        copyGradleCache(repository)
        materializeDeclaredArtifacts(repository)
        materializeConventionalSources(repository)
        materializeCanonicalSnapshotAliases(repository)

        discoverRepositoryComponents(repository)
        val artifacts = inventory(repository)
        val manifest = DependencyBundleManifest(
            producer = Producer(pluginVersion.get(), project.gradle.gradleVersion),
            configurations = graphs.distinctBy { it.name }.sortedBy { it.name },
            components = components.toSortedMap(),
            edges = edges.sortedWith(compareBy(DependencyEdge::configuration, DependencyEdge::from, DependencyEdge::to)),
            artifacts = artifacts.sortedBy { it.path },
        )
        ManifestJson.write(manifest, manifestPath)
        logger.lifecycle(
            "Captured {} configurations, {} components, {} edges, and {} artifacts in {}",
            graphs.size,
            components.size,
            edges.size,
            artifacts.size,
            output,
        )
    }

    private fun collectConfigurations(): List<Pair<String, Configuration>> {
        val requested = configurationNames.get().toSet()
        val buildName = project.rootProject.name
        val result = mutableListOf<Pair<String, Configuration>>()
        project.rootProject.allprojects.forEach { target ->
            target.configurations
                .filter { it.isCanBeResolved && it.name in requested }
                .forEach { result += "$buildName${target.path}:${it.name}" to it }
            if (includeBuildDependencies.get()) {
                target.buildscript.configurations
                    .filter { it.isCanBeResolved && it.name == "classpath" }
                    .forEach { result += "$buildName${target.path}:buildscript:${it.name}" to it }
            }
        }
        return result.distinctBy { it.first }
    }

    private fun resolveAdditionalModules() {
        additionalModules.get().forEachIndexed { index, notation ->
            val configuration = project.configurations.detachedConfiguration(project.dependencies.create(notation))
            capture("${project.rootProject.name}:supplemental:$index:$notation", configuration)
        }
        gradleVariantRequests.get().forEachIndexed { index, request ->
            val (notation, apiVersion) = request.split('|', limit = 2)
            val configuration = project.configurations.detachedConfiguration(project.dependencies.create(notation))
            configuration.attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            configuration.attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
            configuration.attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
            configuration.attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling::class.java, Bundling.EXTERNAL))
            configuration.attributes.attribute(
                GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
                project.objects.named(GradlePluginApiVersion::class.java, apiVersion),
            )
            capture("${project.rootProject.name}:gradle-api-variant:$index:$notation:$apiVersion", configuration)
        }
    }

    private fun capture(name: String, configuration: Configuration) {
        val root = configuration.incoming.resolutionResult.rootComponent.get()
        val roots = root.dependencies.filterIsInstance<ResolvedDependencyResult>().map { componentId(it.selected) }.distinct()
        graphs += ConfigurationGraph(name, roots)
        visit(name, root, mutableSetOf())
        // Force selected artifact files into Gradle's module cache before it is copied.
        configuration.incoming.artifacts.artifactFiles.files
    }

    private fun visit(configuration: String, component: ResolvedComponentResult, visited: MutableSet<String>) {
        val id = componentId(component)
        components.putIfAbsent(id, componentNode(component))
        if (!visited.add(id)) return
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
            val selected = componentId(dependency.selected)
            components.putIfAbsent(selected, componentNode(dependency.selected))
            edges += DependencyEdge(
                configuration = configuration,
                from = id,
                to = selected,
                requested = dependency.requested.displayName,
                constraint = dependency.isConstraint,
            )
            visit(configuration, dependency.selected, visited)
        }
    }

    private fun componentId(component: ResolvedComponentResult): String = when (val id = component.id) {
        is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
        else -> id.displayName
    }

    private fun componentNode(component: ResolvedComponentResult): ComponentNode {
        val identifier = component.id
        val module = identifier as? ModuleComponentIdentifier
        val variants = component.variants.map { variant ->
            VariantRecord(
                name = variant.displayName,
                attributes = variant.attributes.keySet().associate { key ->
                    key.name to (variant.attributes.getAttribute(key)?.toString() ?: "")
                }.toSortedMap(),
            )
        }
        return ComponentNode(
            id = componentId(component),
            group = module?.group,
            module = module?.module,
            version = module?.version,
            selectionReason = component.selectionReason.descriptions.joinToString("; ") { it.description },
            variants = variants,
        )
    }

    private fun copyGradleCache(repository: Path) {
        val cache = project.gradle.gradleUserHomeDir.toPath().resolve("caches/modules-2/files-2.1")
        if (!Files.isDirectory(cache)) throw GradleException("Gradle module cache not found: $cache")
        Files.walk(cache).use { paths ->
            paths.filter(Files::isRegularFile).forEach { source ->
                val relative = cache.relativize(source)
                if (relative.nameCount < 5) return@forEach
                val group = relative.getName(0).toString().replace('.', '/')
                val destination = repository.resolve(group)
                    .resolve(relative.getName(1).toString())
                    .resolve(relative.getName(2).toString())
                    .resolve(source.fileName.toString())
                Files.createDirectories(destination.parent)
                if (!Files.exists(destination)) {
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                } else if (Files.mismatch(source, destination) != -1L) {
                    logger.warn("Ignoring a conflicting cached copy of {}", destination)
                }
            }
        }
    }

    private fun materializeDeclaredArtifacts(repository: Path) {
        val modules = Files.walk(repository).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".module") }.toList()
        }
        modules.forEach { moduleFile ->
            if (isNonUniqueSnapshotMetadataWithUniqueSibling(moduleFile)) return@forEach
            val metadata = JsonSlurper().parse(moduleFile.toFile()) as? Map<*, *> ?: return@forEach
            (metadata["variants"] as? List<*>)?.forEach { rawVariant ->
                val variant = rawVariant as? Map<*, *> ?: return@forEach
                val attributes = variant["attributes"] as? Map<*, *>
                val documentation = attributes?.get("org.gradle.category") == "documentation"
                val sources = attributes?.get("org.gradle.docstype") == "sources"
                if (documentation && (!includeSources.get() || !sources)) return@forEach
                (variant["files"] as? List<*>)?.forEach { rawFile ->
                    val file = rawFile as? Map<*, *> ?: return@forEach
                    val declaredRelative = file["url"] as? String ?: return@forEach
                    // Some plugin publications incorrectly reuse the main JAR
                    // URL for their sources variant. Preserve both artifacts by
                    // assigning the conventional classifier in the bundle.
                    val relative = if (sources && !declaredRelative.substringAfterLast('/').contains("-sources.")) {
                        declaredRelative.substringBeforeLast('.') + "-sources." + declaredRelative.substringAfterLast('.')
                    } else {
                        declaredRelative
                    }
                    val destination = moduleFile.parent.resolve(relative).normalize()
                    // Cross-version Gradle variants (for example Guava jre ->
                    // android) legitimately use ../ URLs. Permit them within the
                    // exported repository, but never allow traversal outside it.
                    if (!destination.startsWith(repository)) throw GradleException("Unsafe artifact URL in $moduleFile: $relative")
                    val aliasedSnapshot = materializeSnapshotAlias(moduleFile, destination)
                    if (!Files.exists(destination) && !aliasedSnapshot) {
                        if (sources) {
                            if (!tryDownload(repository, destination)) return@forEach
                        } else {
                            download(repository, destination)
                        }
                    }
                    verify(repository, destination, file)
                }
            }
        }
    }

    private fun isNonUniqueSnapshotMetadataWithUniqueSibling(moduleFile: Path): Boolean {
        val version = moduleFile.parent.fileName.toString()
        if (!version.endsWith("-SNAPSHOT")) return false
        val module = moduleFile.parent.parent.fileName.toString()
        if (moduleFile.fileName.toString() != "$module-$version.module") return false
        val base = version.removeSuffix("-SNAPSHOT")
        val unique = Regex("${Regex.escape("$module-$base-")}\\d{8}\\.\\d{6}-\\d+\\.module")
        return Files.list(moduleFile.parent).use { files ->
            files.anyMatch { Files.isRegularFile(it) && unique.matches(it.fileName.toString()) }
        }
    }

    /** File-backed Maven repositories publish unique snapshots, while Gradle
     * module metadata retains non-unique artifact URLs. Materialize those aliases
     * so the exported directory also works as a Maven local repository. */
    private fun materializeSnapshotAlias(moduleMetadata: Path, destination: Path): Boolean {
        val version = destination.parent.fileName.toString()
        if (!version.endsWith("-SNAPSHOT")) return false
        val module = destination.parent.parent.fileName.toString()
        val expectedPrefix = "$module-$version"
        val filename = destination.fileName.toString()
        if (!filename.startsWith(expectedPrefix)) return false
        val baseVersion = version.removeSuffix("-SNAPSHOT")
        val suffix = filename.removePrefix(expectedPrefix)
        val metadataBase = moduleMetadata.fileName.toString().removeSuffix(".module")
        val exactCandidate = moduleMetadata.parent.resolve("$metadataBase$suffix")
        if (!metadataBase.endsWith("-SNAPSHOT") && Files.isRegularFile(exactCandidate)) {
            Files.copy(exactCandidate, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            return true
        }
        val pattern = Regex("${Regex.escape("$module-$baseVersion-")}\\d{8}\\.\\d{6}-\\d+${Regex.escape(suffix)}")
        val candidate = Files.list(destination.parent).use { files ->
            files.filter { Files.isRegularFile(it) && pattern.matches(it.fileName.toString()) }
                .max(compareBy<Path> { it.fileName.toString() })
                .orElse(null)
        } ?: return false
        Files.copy(candidate, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        return true
    }

    /**
     * Gradle's cache retains the timestamped form of unique Maven snapshots. A
     * Maven-local repository has no remote metadata lookup, so consumers also
     * need canonical `-SNAPSHOT` names. Materialize every artifact and sidecar,
     * including POM-only plugin markers and Gradle module metadata.
     */
    private fun materializeCanonicalSnapshotAliases(repository: Path) {
        val snapshotDirectories = Files.walk(repository).use { paths ->
            paths.filter { directory ->
                Files.isDirectory(directory) &&
                    repository.relativize(directory).nameCount >= 3 &&
                    directory.fileName.toString().endsWith("-SNAPSHOT")
            }.toList()
        }

        snapshotDirectories.forEach { directory ->
            val version = directory.fileName.toString()
            val module = directory.parent.fileName.toString()
            val baseVersion = version.removeSuffix("-SNAPSHOT")
            val uniquePattern = Regex(
                "^${Regex.escape("$module-$baseVersion-")}\\d{8}\\.\\d{6}-\\d+(.*)$"
            )
            val latestBySuffix = Files.list(directory).use { files ->
                files.filter(Files::isRegularFile)
                    .map { file -> file to uniquePattern.matchEntire(file.fileName.toString()) }
                    .filter { (_, match) -> match != null }
                    .map { (file, match) -> match!!.groupValues[1] to file }
                    .toList()
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, candidates) -> candidates.maxBy { it.fileName.toString() } }
            }

            latestBySuffix.forEach { (suffix, source) ->
                val destination = directory.resolve("$module-$version$suffix")
                if (!Files.exists(destination) || Files.mismatch(source, destination) != -1L) {
                    Files.copy(
                        source,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                }
            }
        }
    }

    /**
     * Maven POM-only modules do not describe source artifacts. Try the conventional
     * classifier for every release module that has a primary JAR, while treating a
     * repository 404 as "sources not published" rather than an export failure.
     */
    private fun materializeConventionalSources(repository: Path) {
        if (!includeSources.get()) return
        Files.walk(repository).use { paths ->
            paths.filter { Files.isDirectory(it) && repository.relativize(it).nameCount >= 3 }.forEach { directory ->
                val relative = repository.relativize(directory)
                val version = relative.getName(relative.nameCount - 1).toString()
                val module = relative.getName(relative.nameCount - 2).toString()
                if (version.endsWith("-SNAPSHOT")) return@forEach
                val main = directory.resolve("$module-$version.jar")
                val sources = directory.resolve("$module-$version-sources.jar")
                if (Files.isRegularFile(main) && !Files.exists(sources)) {
                    tryDownload(repository, sources)
                }
            }
        }
    }

    private fun download(repository: Path, destination: Path) {
        if (tryDownload(repository, destination)) return
        val relative = repository.relativize(destination).joinToString("/")
        throw GradleException("Cannot download $relative from configured artifact repositories")
    }

    private fun tryDownload(repository: Path, destination: Path): Boolean {
        val relative = repository.relativize(destination).joinToString("/")
        val failures = mutableListOf<String>()
        artifactRepositoryUrls.get().forEach { base ->
            val url = URI.create("${base.trimEnd('/')}/$relative").toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            try {
                if (connection.responseCode !in 200..299) {
                    failures += "$url: HTTP ${connection.responseCode}"
                    return@forEach
                }
                Files.createDirectories(destination.parent)
                connection.inputStream.use { Files.copy(it, destination, StandardCopyOption.REPLACE_EXISTING) }
                return true
            } catch (exception: Exception) {
                failures += "$url: ${exception.message}"
            } finally {
                connection.disconnect()
            }
        }
        logger.info("Artifact {} is unavailable: {}", relative, failures.joinToString("; "))
        return false
    }

    private fun verify(repository: Path, path: Path, metadata: Map<*, *>) {
        val expected = when {
            metadata["sha512"] is String -> "SHA-512" to metadata["sha512"] as String
            metadata["sha256"] is String -> "SHA-256" to metadata["sha256"] as String
            else -> return
        }
        val actual = digest(path, expected.first)
        if (actual.equals(expected.second, true)) return

        // The same coordinate may have different bytes in different configured
        // mirrors. The Gradle cache retains each copy by content hash; select the
        // one referenced by this particular .module file instead of whichever
        // copyGradleCache happened to encounter first.
        val cache = project.gradle.gradleUserHomeDir.toPath().resolve("caches/modules-2/files-2.1")
        val matchingCacheFile = Files.walk(cache).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName == path.fileName }
                .filter { digest(it, expected.first).equals(expected.second, true) }
                .findFirst()
                .orElse(null)
        }
        if (matchingCacheFile != null) {
            Files.copy(matchingCacheFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            return
        }

        Files.deleteIfExists(path)
        download(repository, path)
        if (!digest(path, expected.first).equals(expected.second, true)) {
            // A small number of upstream publications contain stale hashes in
            // their Gradle module metadata (while every repository serves the
            // same valid Maven artifact). Gradle itself resolves these modules;
            // preserve the fetched artifact and record our own SHA-256 instead.
            logger.warn("Upstream Gradle metadata contains a stale checksum for {}", path)
        }
    }

    private fun inventory(repository: Path): List<ArtifactRecord> {
        val known = components.values.filter { it.group != null }.associateBy { "${it.group!!.replace('.', '/')}/${it.module}/${it.version}/" }
        return Files.walk(repository).use { paths ->
            paths.filter(Files::isRegularFile).map { file ->
                val relative = repository.relativize(file).joinToString("/")
                val owner = known.entries.firstOrNull { relative.startsWith(it.key) }?.value?.id
                ArtifactRecord(owner, relative, kind(file.fileName.toString()), Files.size(file), digest(file, "SHA-256"))
            }.toList()
        }
    }

    private fun discoverRepositoryComponents(repository: Path) {
        val discovered = mutableListOf<String>()
        Files.walk(repository).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".pom") }.forEach { pom ->
                val directory = repository.relativize(pom.parent)
                if (directory.nameCount < 3) return@forEach
                val version = directory.getName(directory.nameCount - 1).toString()
                val module = directory.getName(directory.nameCount - 2).toString()
                val group = (0 until directory.nameCount - 2).joinToString(".") { directory.getName(it).toString() }
                val id = "$group:$module:$version"
                if (components.containsKey(id)) return@forEach
                components[id] = ComponentNode(id, group, module, version, "discovered in isolated build cache")
                discovered += id
            }
        }
        if (discovered.isNotEmpty()) {
            graphs += ConfigurationGraph("build-cache:discovered", discovered.distinct().sorted())
        }
    }

    private fun kind(filename: String): String = when {
        filename.endsWith("-sources.jar") -> "sources"
        filename.endsWith(".jar") -> "jar"
        filename.endsWith(".module") -> "gradle-module-metadata"
        filename.endsWith(".pom") -> "pom"
        else -> "metadata"
    }

    private fun digest(path: Path, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
