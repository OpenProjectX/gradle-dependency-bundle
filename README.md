# Gradle Dependency Bundle

Build a portable Maven-layout repository and a dependency graph from a Gradle build, then check which exact artifacts are absent from a restricted JFrog repository.

```kotlin
plugins {
    id("org.openprojectx.gradle.dependency.bundle") version "0.1.1"
}

dependencyBundle {
    configurations.addAll("runtimeClasspath", "testRuntimeClasspath")
    includeBuildDependencies.set(true)
    includeSources.set(true)
}
```

```shell
./gradlew exportDependencyBundle
./gradlew dependencyBundleReport
./gradlew auditArtifactRepository -PartifactRepositoryUrl=https://jfrog.example/artifactory/maven
```

The export contains `build/dependency-bundle/m2/repository`, `dependency-graph.json`, and the dependencies-style `dependency-graph.txt`. The graph retains configurations, selected components, edges, selection reasons, Gradle variant attributes, and every artifact path/checksum.

This repository also builds a runnable Jib image. Its portable repository is at `/m2/repository`; its default command audits `/dependency-bundle/dependency-graph.json` using `JFROG_URL`, `JFROG_USERNAME`, and `JFROG_PASSWORD`.

See the [user guide](doc/user-guide.adoc) for build-tool capture, independent-build usage, offline verification, image extraction, and JFrog reports.
