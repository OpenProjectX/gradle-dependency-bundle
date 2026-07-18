pluginManagement {
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
