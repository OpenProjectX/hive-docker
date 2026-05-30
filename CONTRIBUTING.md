# Contributing

This repository builds Docker images only. Do not add Maven Central, Sonatype, signing, or jar publishing flows unless the project direction changes explicitly.

## Development Rules

- Keep vanilla images clean: only Apache Hive/HMS, Apache Hadoop, JDK, and minimal runtime OS packages.
- Put project-specific dependencies in custom images.
- Keep vanilla image tags independent from the Gradle project release version.
- Keep custom image tags tied to the Gradle release version.
- Preserve the release-commit guard in workflows before enabling or changing push triggers.

## Local Setup

Use the shared Gradle cache. The Gradle build itself can run on JDK 17, while the smoke test toolchain uses JDK 21 for Hive 4.2.0 client jars.

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :image:tasks --all
```

For local Docker builds, prefer local tarballs:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerBuildVanillaHive420 \
  -PuseLocalTarballs=true
```

Expected local tarball directory:

```text
/home/coder/Downloads
```

Override it if needed:

```bash
-PlocalTarballDir=/path/to/tarballs
```

## Validation

Before opening a change, run:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:tasks --all \
  :image:generateDockerfiles \
  -PuseLocalTarballs=true
```

Compile the smoke test client:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:testClasses
```

Run the full smoke test when a custom HMS image is available or can be built locally:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest
```

The custom image release task builds the custom images first, runs the smoke test against the same local custom standalone metastore tag, then pushes images:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerReleaseImages \
  -PimageRegistry=ghcr.io/openprojectx
```

Build the local vanilla and custom images as part of the smoke test only when needed:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest \
  -Psmoke.buildImage=true \
  -PuseLocalTarballs=true
```

The smoke test uses a Hive metastore client to connect to the custom standalone metastore container, create a database, and list it back through HMS. The client test JVM is JDK 21 because Hive 4.2.0 client jars are Java 21 bytecode.

For workflow or tag changes, inspect generated Dockerfiles:

```bash
sed -n '1,40p' image/build/docker/Hive420/Dockerfile.custom
sed -n '1,80p' image/build/docker/Hive420/Dockerfile.vanilla
```

Expected tag behavior:

```text
hive-vanilla:4.2.0-hadoop-3.4.2-jdk21
hive:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
```

## Release Process

Vanilla base images are published manually from the **Vanilla Base Images** workflow.

Custom images are released by the **Custom Images** workflow. On push to `master`, the workflow derives:

- `release_version` from `gradle.properties` by removing `-SNAPSHOT`
- `next_version` by incrementing the patch version and appending `-SNAPSHOT`

Manual workflow dispatch may override these values.

## Compatibility Notes

Hive 4 and standalone metastore 4.2.0 are the primary targets for the current GCS connector setup.

Hive 3.1.3 with JDK 17 and modern GCS libraries may require separate compatibility work. Hive 4.2.0 and HMS 4.2.0 require JDK 21 at runtime because their classes are Java 21 bytecode. Keep Hive 3 changes isolated so Hive 4 and HMS 4 releases are not blocked by Hive 3-specific dependency constraints.
