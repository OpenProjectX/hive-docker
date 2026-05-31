# Contributing

This repository builds Docker images and the `hive-docker-testcontainers` helper jar. Do not add Maven Central, Sonatype, or signing flows unless the project direction changes explicitly.

## Development Rules

- Keep vanilla images clean: only Apache Hive/HMS, Apache Hadoop, JDK, and minimal runtime OS packages.
- Put project-specific dependencies in custom images.
- Keep vanilla image tags independent from the Gradle project release version.
- Keep custom image tags tied to the Gradle release version, with the project version at the end of the tag.
- Preserve the release-commit guard in workflows before enabling or changing push triggers.
- Keep database drivers in custom images unless the project explicitly decides to make them part of vanilla.
- Keep custom image dependency replacement in the Gradle jar-install model in `image/build.gradle.kts`. Do not add one-off inline shell blocks for new conflict families.

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

Custom image jar replacement defaults to `-Pimage.jarConflictStrategy=remove`. For Hive 3 this removes configured old Hive-side jars before copying the GCS-compatible versions. To add user-provided high-priority jars during a build, stage them with `-Pimage.priorityJarDir=/path/to/jars` and list any old target jars to remove with `-Pimage.priorityJarRemovePatterns='old-lib-*.jar,another-lib-*.jar'`.

Use the runtime `/tmp/ext-jars` mount only for quick experiments. For releaseable images, bake replacement jars into the custom image with the Gradle properties above so the final jar set is inspectable and reproducible.

For runtime troubleshooting, prefer `HIVE_LOG_LEVEL=DEBUG` or a mounted log4j2 file through `HIVE_LOG4J2_CONFIGURATION_FILE` before changing the baked image defaults. Keep default image logging at `INFO`.

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

Run the Testcontainers helper jar tests. These include Spark/Iceberg S3 and GCS storage smoke tests by default:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :testcontainers:test
```

The default run depends on `:image:dockerBuildCustomHive313`, so it tests the helper jar against the current custom Hive 3 snapshot image running HMS through `HiveMetastoreContainer`. It does not build vanilla images by default; keep the matching vanilla base image available locally or in the configured registry.

Skip the Docker-backed storage smoke tests only when you need a quick local helper-jar check:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :testcontainers:test \
  -Ptestcontainers.skipStorageSmoke=true
```

Use `-Ptestcontainers.hmsImage=...` to test against an existing HMS image and skip the image build dependency. The storage tests start `localstack/localstack:4.14.0` for S3 and `fsouza/fake-gcs-server:1.54` for GCS, then configure Spark Iceberg with a Hive catalog through `HiveMetastoreContainer`.

Run the full smoke test when the current custom image tags already exist locally or in the registry:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest
```

The default smoke subjects are `hive3`, `hive4`, and `hive-standalone-metastore-4`. Limit the run with `-Psmoke.subjects=hive-standalone-metastore-4` or override image tags with `-Psmoke.image.<subject>=...`.

The default image tag includes the current Gradle project version. If that tag has not been built or published yet, build it locally during the test:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive3:test \
  -Psmoke.subjects=hive3 \
  -Psmoke.buildImage=true \
  -PimageRegistry=ghcr.io/openprojectx

env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive4:test \
  -Psmoke.subjects=hive4,hive-standalone-metastore-4 \
  -Psmoke.buildImage=true \
  -PimageRegistry=ghcr.io/openprojectx
```

Use `-Dsmoke.containerLogs=true` on any smoke command when debugging container startup.

The custom image release task builds the custom images first, runs the smoke tests against the same local custom image tags, then pushes images:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerReleaseImages \
  -PimageRegistry=ghcr.io/openprojectx
```

The custom-images workflow runs the Gradle release task without skipping storage smoke tests, so the release pipeline also runs the Spark/Iceberg S3 and GCS smoke tests before publishing the helper jar.

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
IMAGE=ghcr.io/openprojectx/hive:3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17-0.1.1-SNAPSHOT

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

Also run the PostgreSQL-backed HMS smoke tests after rebuilding the custom images:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :image:dockerBuildCustomHive313 \
  -PimageRegistry=ghcr.io/openprojectx

env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :image:dockerBuildCustomStandaloneMetastore420 \
  -PimageRegistry=ghcr.io/openprojectx

env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive3:test \
  --tests 'org.openprojectx.hive.docker.smoke.hive3.Hive3MetastoreSmokeTest.hive3ImageInitializesPostgresSchemaAndAcceptsHive3MetastoreClientRequests' \
  -Psmoke.subjects=hive3 \
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
hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

Clean up older local snapshot images after repeated local builds. Docker lists images newest first, so this keeps the newest local snapshot tag per image repository.

Dry run:

```bash
docker images 'ghcr.io/openprojectx/hive*' \
  --filter reference='*:*SNAPSHOT*' \
  --format '{{.Repository}} {{.Tag}} {{.ID}} {{.CreatedAt}}' \
  | awk '!seen[$1]++ { print "KEEP   " $0; next } { print "REMOVE " $0 }'
```

Remove older local snapshots:

```bash
docker images 'ghcr.io/openprojectx/hive*' \
  --filter reference='*:*SNAPSHOT*' \
  --format '{{.Repository}} {{.Tag}}' \
  | awk 'seen[$1]++ { print $1 ":" $2 }' \
  | xargs -r docker rmi
```

If Docker reports that an old image is still used by a stopped container, run `docker container prune` and retry the cleanup command.

## Release Process

Vanilla base images are published manually from the **Vanilla Base Images** workflow.

That workflow restores `.cache/apache-tarballs` from GitHub Actions cache, downloads any missing Hive/HMS/Hadoop tarballs, then passes `-PuseLocalTarballs=true` and `-PlocalTarballDir` to Gradle. Keep that path stable so repeated manual vanilla builds reuse cached tarballs.

Custom images are released by the **Custom Images** workflow. On push to `master`, the workflow derives:

- `release_version` from `gradle.properties` by removing `-SNAPSHOT`
- `next_version` by incrementing the patch version and appending `-SNAPSHOT`

Manual workflow dispatch may override these values.

The same Gradle `release` run publishes `:testcontainers` to GitHub Packages. The workflow exports `GITHUB_TOKEN`; local publishing needs equivalent credentials:

```bash
env GITHUB_ACTOR=<user> GITHUB_TOKEN=<token> GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :testcontainers:publish
```

## Compatibility Notes

Hive 4 and standalone metastore 4.2.0 are the primary targets for the current GCS connector setup.

Hive 3.1.3 with JDK 17 and modern GCS libraries may require separate compatibility work. Hive 4.2.0 and HMS 4.2.0 require JDK 21 at runtime because their classes are Java 21 bytecode. Keep Hive 3 changes isolated so Hive 4 and HMS 4 releases are not blocked by Hive 3-specific dependency constraints.
