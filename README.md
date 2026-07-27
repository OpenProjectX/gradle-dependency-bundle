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

Apply the plugin to the root project to aggregate matching configurations from every project. Apply it to a subproject such as `:app` to capture only that project; run `./gradlew :app:dependencyBundleReport` and find the result under `app/build/dependency-bundle`.

Only cache entries belonging to components in the captured graphs and their Maven parent/BOM metadata closure are copied. A shared Gradle user home can therefore be used without bundling unrelated artifacts from other builds. Delete an output created by plugin `0.1.1` or older before regenerating it, because bundle outputs are mergeable and intentionally retain existing files.

Settings plugins and separate builds such as `buildSrc` are not visible through a project plugin's resolution graph. A bootstrap pass running with a fresh, dedicated `GRADLE_USER_HOME` may set `includeUntrackedBuildDependencies.set(true)` to include those cache entries. Do not enable this option with a shared Gradle home.

This repository also builds a runnable Jib image. Its portable repository is at `/m2/repository`; its default command audits `/dependency-bundle/dependency-graph.json` using `JFROG_URL`, `JFROG_USERNAME`, and `JFROG_PASSWORD`.

See the [user guide](doc/user-guide.adoc) for build-tool capture, independent-build usage, offline verification, image extraction, and JFrog reports.
