pluginManagement {
    val bundledPluginVersion = providers.gradleProperty("bundlePluginVersion")
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "org.openprojectx.gradle.dependency.bundle") {
            useVersion(bundledPluginVersion.get())
        }
    }
    repositories {
        maven { url = uri(providers.gradleProperty("bundleRepository").get()) }
        if (!providers.gradleProperty("bundleOfflineOnly").isPresent) {
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("bundleRepository").get()) }
        if (!providers.gradleProperty("bundleOfflineOnly").isPresent) {
            mavenCentral()
        }
    }
}

rootProject.name = "dependency-bundle-example"
