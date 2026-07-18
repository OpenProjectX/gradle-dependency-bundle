plugins {
    kotlin("jvm") version "2.2.21"
    id("org.openprojectx.gradle.dependency.bundle") version "0.1.0-SNAPSHOT"
    application
}

dependencies {
    implementation("com.squareup:kotlinpoet:2.3.0")
}

dependencyBundle {
    configurations.add("runtimeClasspath")
    includeBuildDependencies.set(true)
    includeSources.set(true)
    providers.gradleProperty("bundleOutput").orNull?.let { outputDirectory.set(file(it)) }
}

application {
    mainClass.set("example.MainKt")
}
