package org.openprojectx.gradle.dependency.bundle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty
import javax.inject.Inject

abstract class DependencyBundleExtension @Inject constructor(objects: ObjectFactory) {
    val configurations: ListProperty<String> = objects.listProperty(String::class.java).convention(listOf("runtimeClasspath"))
    val includeBuildDependencies: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val includeSources: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val additionalModules: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())
    val gradleVariantRequests: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())
    val artifactRepositoryUrls: ListProperty<String> = objects.listProperty(String::class.java).convention(
        listOf("https://repo.maven.apache.org/maven2", "https://plugins.gradle.org/m2"),
    )
    val outputDirectory: DirectoryProperty = objects.directoryProperty()

    fun module(notation: String) {
        additionalModules.add(notation)
    }

    fun gradleApiVariants(notation: String, versions: Iterable<String>) {
        versions.forEach { gradleVariantRequests.add("$notation|$it") }
    }
}
