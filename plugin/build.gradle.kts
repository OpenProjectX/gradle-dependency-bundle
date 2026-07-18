plugins {
    id("buildsrc.convention.kotlin-jvm")
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":repository-core"))
    testImplementation(gradleTestKit())
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

gradlePlugin {
    plugins {
        create("dependencyBundle") {
            id = "org.openprojectx.gradle.dependency.bundle"
            implementationClass = "org.openprojectx.gradle.dependency.bundle.DependencyBundlePlugin"
            displayName = "Gradle Dependency Bundle"
            description = "Captures dependency graphs, exports Maven repositories, and audits restricted repositories"
        }
    }
}
