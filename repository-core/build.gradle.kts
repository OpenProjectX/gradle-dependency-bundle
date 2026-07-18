plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    api(libs.jacksonDatabind)
    api(libs.jacksonKotlin)
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}
