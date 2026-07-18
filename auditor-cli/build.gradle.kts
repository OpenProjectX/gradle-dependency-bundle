plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
    alias(libs.plugins.jib)
}

dependencies {
    implementation(project(":repository-core"))
}

application {
    mainClass.set("org.openprojectx.gradle.dependency.bundle.cli.MainKt")
}

val bundleDirectory = rootProject.layout.buildDirectory.dir("dependency-bundle")
val imageMetadataDirectory = rootProject.layout.buildDirectory.dir("dependency-bundle-image")

jib {
    from.image = "eclipse-temurin:17-jre"
    to.image = providers.gradleProperty("dependencyBundleImage")
        .orElse("ghcr.io/openprojectx/gradle-dependency-bundle:${project.version}")
        .get()
    container {
        mainClass = application.mainClass.get()
        args = listOf("--manifest", "/dependency-bundle/dependency-graph.json")
        labels = mapOf(
            "org.opencontainers.image.title" to "Gradle Dependency Bundle auditor",
            "org.opencontainers.image.source" to "https://github.com/OpenProjectX/gradle-dependency-bundle",
            "org.opencontainers.image.version" to project.version.toString(),
        )
        user = "1000:1000"
        workingDirectory = "/tmp"
    }
    extraDirectories {
        paths {
            path {
                setFrom(bundleDirectory.map { it.dir("m2") }.get().asFile.toPath())
                into = "/m2"
            }
            path {
                setFrom(imageMetadataDirectory.get().asFile.toPath())
                into = "/dependency-bundle"
            }
        }
        permissions = mapOf(
            "/m2" to "755",
            "/m2/repository" to "755",
            "/dependency-bundle" to "755",
        )
    }
}

tasks.matching { it.name == "jib" || it.name == "jibDockerBuild" || it.name == "jibBuildTar" }
    .configureEach { dependsOn(rootProject.tasks.named("stageDependencyBundleImage")) }
