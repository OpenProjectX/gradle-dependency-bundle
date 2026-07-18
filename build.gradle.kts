import net.researchgate.release.ReleaseExtension
import org.asciidoctor.gradle.jvm.AsciidoctorTask
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.Sign
import org.gradle.api.tasks.Exec

plugins {
    `maven-publish`
    signing
    id("org.asciidoctor.jvm.convert") version "4.0.2"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("net.researchgate.release") version "3.1.0"
}

tasks.named<AsciidoctorTask>("asciidoctor") {
    group = "documentation"
    description = "Generates HTML documentation from AsciiDoc sources"
    notCompatibleWithConfigurationCache("Asciidoctor task configuration is not configuration-cache compatible in this build")
    setSourceDir(file("doc"))
    sources {
        include("user-guide.adoc")
    }
    setOutputDir(layout.buildDirectory.dir("docs").get().asFile)
    doLast {
        copy {
            from(layout.buildDirectory.file("docs/user-guide.html"))
            into(layout.buildDirectory.dir("docs"))
            rename { "index.html" }
        }
    }
}

val syncDocsVersion by tasks.registering {
    val versionedDocFiles = layout.files(
        layout.projectDirectory.file("README.md"),
        layout.projectDirectory.file("doc/user-guide.adoc"),
    )

    group = "documentation"
    description = "Syncs plugin version snippets in README and user guide to project.version"

    inputs.property("pluginVersion", project.version.toString())
    outputs.files(versionedDocFiles)
    inputs.files(versionedDocFiles)

    doLast {
        val pluginVersion = inputs.properties["pluginVersion"].toString()
        val pluginVersionSnippetRegex =
            Regex("""(id\("${Regex.escape("org.openprojectx.gradle.dependency.bundle")}"\) version ")([^"]+)(")""")

        inputs.files.files.forEach { file ->
            if (!file.exists()) return@forEach

            val original = file.readText()
            val updated = pluginVersionSnippetRegex.replace(original) { match ->
                "${match.groupValues[1]}$pluginVersion${match.groupValues[3]}"
            }

            if (original != updated) {
                file.writeText(updated)
            }
        }
    }
}

allprojects {
    group = "org.openprojectx.gradle.dependency.bundle"
}

subprojects {
    tasks.register<DependencyReportTask>("allDependencies") {}

    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension>("java") {
            withSourcesJar()
            withJavadocJar()
        }

        tasks.withType(Javadoc::class.java).configureEach {
            isFailOnError = false
        }

        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                maven {
                    name = "bundle"
                    url = rootProject.layout.buildDirectory.dir("dependency-bundle/m2/repository").get().asFile.toURI()
                }
            }
            publications {
                if (project.name != "plugin" && findByName("mavenJava") == null) {
                    create<MavenPublication>("mavenJava") {
                        from(components["java"])
                        artifactId = project.name
                    }
                }
            }

            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(
                        when (project.name) {
                            "plugin" -> "Gradle Dependency Bundle"
                            "core" -> "Gradle Dependency Bundle Core"
                            "maven-plugin" -> "Gradle Dependency Bundle Maven Plugin"
                            else -> project.name
                        }
                    )
                    description.set("Gradle Dependency Bundle Gradle plugin")
                    url.set("https://github.com/OpenProjectX/gradle-dependency-bundle")

                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }

                    developers {
                        developer {
                            id.set("OpenProjectX")
                            name.set("OpenProjectX")
                            email.set("admin@openprojectx.org")
                        }
                    }

                    scm {
                        url.set("https://github.com/OpenProjectX/gradle-dependency-bundle")
                        connection.set("scm:git:https://github.com/OpenProjectX/gradle-dependency-bundle.git")
                        developerConnection.set("scm:git:ssh://git@github.com:OpenProjectX/gradle-dependency-bundle.git")
                    }
                }
            }
        }
    }

    extensions.configure<SigningExtension>("signing") {
        val keyFile = System.getenv("SIGNING_KEY_FILE")
        val keyPass = System.getenv("SIGNING_KEY_PASSWORD")

        if (!keyFile.isNullOrBlank()) {
            val keyText = file(keyFile).readText()
            useInMemoryPgpKeys(keyText, keyPass)

            val publishing = extensions.findByType(PublishingExtension::class.java)
            if (publishing != null) {
                sign(publishing.publications)
            }
        }
    }

    tasks.withType<PublishToMavenRepository>().configureEach {
        dependsOn(tasks.withType<Sign>())
    }
}

val bundleDirectory = layout.buildDirectory.dir("dependency-bundle")
val bundleRepository = bundleDirectory.map { it.dir("m2/repository") }
// Keep the resolver home outside ExportDependencyBundleTask's output directory.
// Gradle may clean task outputs before execution, which would otherwise remove
// the running wrapper distribution and daemon registry from underneath itself.
val bundleGradleHome = layout.buildDirectory.dir("dependency-bundle-gradle-home")

val publishBundleArtifacts by tasks.registering {
    group = "dependency bundle"
    description = "Publishes this build's plugin and libraries into the bundle repository."
}

gradle.projectsEvaluated {
    publishBundleArtifacts.configure {
        dependsOn(subprojects.flatMap { subproject ->
            subproject.tasks.matching { task ->
                task.name.endsWith("ToBundleRepository") && task.name.startsWith("publish")
            }.toList()
        })
    }
}

val captureRootDependencyBundle by tasks.registering(Exec::class) {
    group = "dependency bundle"
    dependsOn(publishBundleArtifacts)
    val repository = bundleRepository.get().asFile.absolutePath
    val output = bundleDirectory.get().asFile.absolutePath
    val gradleHome = bundleGradleHome.get().asFile.absolutePath
    commandLine(
        "./gradlew", "--no-daemon", "--no-configuration-cache",
        "--init-script", "gradle/dependency-bundle.init.gradle",
        "-DbundleRepository=$repository",
        "-DbundleOutput=$output",
        "-DbundlePluginVersion=${project.version}",
        "-DbundleProjectDir=${rootDir.absolutePath}",
        "dependencyBundleReport",
    )
    environment("GRADLE_USER_HOME", gradleHome)
}

val buildExampleAgainstBundle by tasks.registering(Exec::class) {
    group = "dependency bundle"
    dependsOn(captureRootDependencyBundle)
    val repository = bundleRepository.get().asFile.absolutePath
    val gradleHome = bundleGradleHome.get().asFile.absolutePath
    commandLine(
        "./gradlew", "--no-daemon", "--no-configuration-cache",
        "-p", "example",
        "-PbundleRepository=$repository",
        "-PbundleOutput=${bundleDirectory.get().asFile.absolutePath}",
        "build",
    )
    environment("GRADLE_USER_HOME", gradleHome)
}

val captureExampleDependencyBundle by tasks.registering(Exec::class) {
    group = "dependency bundle"
    dependsOn(buildExampleAgainstBundle)
    val repository = bundleRepository.get().asFile.absolutePath
    val output = bundleDirectory.get().asFile.absolutePath
    val gradleHome = bundleGradleHome.get().asFile.absolutePath
    commandLine(
        "./gradlew", "--no-daemon", "--no-configuration-cache",
        "-p", "example",
        "-PbundleRepository=$repository",
        "-PbundleOutput=$output",
        "dependencyBundleReport",
    )
    environment("GRADLE_USER_HOME", gradleHome)
}

tasks.register("prepareDependencyBundle") {
    group = "dependency bundle"
    description = "Builds the graph manifest and M2 repository used by the runnable auditor image."
    dependsOn(captureExampleDependencyBundle)
}

val verifyPreparedDependencyBundle by tasks.registering {
    group = "verification"
    description = "Checks that every artifact in the generated manifest exists and matches its size and SHA-256."
    dependsOn("prepareDependencyBundle")
    val manifest = bundleDirectory.map { it.file("dependency-graph.json") }
    val repository = bundleRepository
    inputs.file(manifest)
    inputs.dir(repository)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val document = groovy.json.JsonSlurper().parse(manifest.get().asFile) as Map<String, Any?>
        val artifacts = document["artifacts"] as List<Map<String, Any?>>
        artifacts.forEach { artifact ->
            val relative = artifact.getValue("path").toString()
            val file = repository.get().file(relative).asFile
            check(file.isFile) { "Manifest artifact is absent: $relative" }
            check(file.length() == (artifact.getValue("size") as Number).toLong()) {
                "Manifest size differs for $relative"
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == artifact.getValue("sha256")) { "Manifest checksum differs for $relative" }
        }
        logger.lifecycle("Verified {} bundled artifacts", artifacts.size)
    }
}

tasks.register<Sync>("stageDependencyBundleImage") {
    group = "dependency bundle"
    description = "Stages the graph metadata consumed by the runnable image."
    dependsOn(verifyPreparedDependencyBundle)
    from(bundleDirectory) {
        include("dependency-graph.json", "dependency-graph.txt")
    }
    into(layout.buildDirectory.dir("dependency-bundle-image"))
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("OSSRH_USERNAME"))
            password.set(System.getenv("OSSRH_PASSWORD"))
        }
    }
}

configure<ReleaseExtension> {
    buildTasks.set(
        listOf(
            "syncDocsVersion",
            "publishToSonatype",
            "closeAndReleaseSonatypeStagingRepository",
            ":auditor-cli:jib",
        )
    )
    versionPropertyFile.set("gradle.properties")
    tagTemplate.set("\$name-\$version")

    with(git) {
        requireBranch.set("master")
    }
}
