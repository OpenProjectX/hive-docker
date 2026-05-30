# Contributing

This repository builds Docker images only. Do not add Maven Central, Sonatype, signing, or jar publishing flows unless the project direction changes explicitly.

## Development Rules

- Keep vanilla images clean: only Apache Hive/HMS, Apache Hadoop, JDK, and minimal runtime OS packages.
- Put project-specific dependencies in custom images.
- Keep vanilla image tags independent from the Gradle project release version.
- Keep custom image tags tied to the Gradle release version.
- Preserve the release-commit guard in workflows before enabling or changing push triggers.
- Keep database drivers in custom images unless the project explicitly decides to make them part of vanilla.

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

Run the full smoke test when custom images are available or can be built locally:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest
```

The default smoke subjects are `hive3`, `hive4`, and `hive-standalone-metastore-4`. Limit the run with `-Psmoke.subjects=hive-standalone-metastore-4` or override image tags with `-Psmoke.image.<subject>=...`.

The custom image release task builds the custom images first, runs the smoke tests against the same local custom image tags, then pushes images:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerReleaseImages \
  -PimageRegistry=ghcr.io/openprojectx
```

Build the selected local custom images as part of the smoke test only when needed:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest \
  -Psmoke.buildImage=true \
  -PuseLocalTarballs=true
```

This expects the selected vanilla base images to already exist locally or in the registry. Add `-Psmoke.buildVanillaImage=true` only when the selected vanilla bases must be rebuilt locally.

The smoke test uses a Hive metastore client to connect to each subject container and list databases through HMS. Client dependencies are isolated by Gradle subproject: `:smoke-test:hive3` uses the Hive 3.1.3 client on JDK 17, and `:smoke-test:hive4` uses the Hive 4.2.0 client on JDK 21 for both Hive 4 and standalone HMS 4 subjects.

For workflow or tag changes, inspect generated Dockerfiles:

```bash
sed -n '1,40p' image/build/docker/Hive420/Dockerfile.custom
sed -n '1,80p' image/build/docker/Hive420/Dockerfile.vanilla
```

Inspect built image contents when changing dependency placement:

```bash
IMAGE=ghcr.io/openprojectx/hive:0.1.1-SNAPSHOT-3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17

docker run --rm --entrypoint bash "$IMAGE" -lc '
  find /opt/hadoop/share/hadoop /opt/hive -type f -name "*.jar" | sort
'

docker run --rm --entrypoint bash "$IMAGE" -lc '
  java -version
  /opt/hadoop/bin/hadoop version | head -n 2
  find /opt/hadoop/share/hadoop /opt/hive -type f -name "*.jar" \
    | sort \
    | grep -E "/(hadoop-common|hadoop-aws|aws-java-sdk-bundle|gcs-connector|gcsio|util-hadoop|hive-metastore|postgresql)-"
'
```

When changing PostgreSQL support, confirm the custom image contains the driver and the vanilla image does not:

```bash
docker run --rm --entrypoint bash "$IMAGE" -lc '
  find /opt/hive/lib -type f -name "postgresql-*.jar" | sort
'
```

Also run the PostgreSQL-backed HMS smoke test after rebuilding the custom image:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :image:dockerBuildCustomStandaloneMetastore420 \
  -PimageRegistry=ghcr.io/openprojectx

env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive4:test \
  --tests 'org.openprojectx.hive.docker.smoke.hive4.Hive4MetastoreSmokeTest.hive4ImageInitializesPostgresSchemaAndAcceptsHiveMetastoreClientRequests' \
  -Psmoke.subjects=hive-standalone-metastore-4 \
  -PimageRegistry=ghcr.io/openprojectx
```

Set `-Dsmoke.containerLogs=true` on the test command when you need the HMS container logs in the Gradle output.

Expected tag behavior:

```text
hive-vanilla:4.2.0-hadoop-3.4.2-jdk21
hive:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
```

## Release Process

Vanilla base images are published manually from the **Vanilla Base Images** workflow.

That workflow restores `.cache/apache-tarballs` from GitHub Actions cache, downloads any missing Hive/HMS/Hadoop tarballs, then passes `-PuseLocalTarballs=true` and `-PlocalTarballDir` to Gradle. Keep that path stable so repeated manual vanilla builds reuse cached tarballs.

Custom images are released by the **Custom Images** workflow. On push to `master`, the workflow derives:

- `release_version` from `gradle.properties` by removing `-SNAPSHOT`
- `next_version` by incrementing the patch version and appending `-SNAPSHOT`

Manual workflow dispatch may override these values.

## Compatibility Notes

Hive 4 and standalone metastore 4.2.0 are the primary targets for the current GCS connector setup.

Hive 3.1.3 with JDK 17 and modern GCS libraries may require separate compatibility work. Hive 4.2.0 and HMS 4.2.0 require JDK 21 at runtime because their classes are Java 21 bytecode. Keep Hive 3 changes isolated so Hive 4 and HMS 4 releases are not blocked by Hive 3-specific dependency constraints.
