# hive-docker

Docker image build project for Apache Hive and Hive Standalone Metastore with Apache Hadoop 3.4.2.

The project builds two image layers:

- **Vanilla base images**: Apache Hive/HMS plus Hadoop only. These do not include this project's release version in the tag.
- **Custom images**: built from the vanilla base images and add GCS connector dependencies. These include the Gradle project release version in the tag.

No Maven artifacts are published from this repository. Release means Docker images only.

## Images

| Image | Purpose | Tag format |
| --- | --- | --- |
| `hive-vanilla` | Base Hive image | `<hive>-hadoop-<hadoop>-jdk<jdk>` |
| `hive-standalone-metastore-vanilla` | Base standalone HMS image | `<hive>-hadoop-<hadoop>-jdk<jdk>` |
| `hive` | Custom Hive image with GCS and PostgreSQL JDBC libraries | `<project>-<hive>-hadoop-<hadoop>-gcs-<gcs>-jdk<jdk>` |
| `hive-standalone-metastore` | Custom standalone HMS image with GCS and PostgreSQL JDBC libraries | `<project>-<hive>-hadoop-<hadoop>-gcs-<gcs>-jdk<jdk>` |

Current versions are managed in [gradle/libs.versions.toml](gradle/libs.versions.toml):

- Hadoop: `3.4.2`
- GCS connector: `4.0.4`
- PostgreSQL JDBC: `42.7.4`
- Hive: `3.1.3`, `4.2.0`
- Standalone metastore: `4.2.0`
- JDK: `17` for Hive 3.1.3, `21` for Hive 4.2.0/HMS 4.2.0

Example tags:

```text
ghcr.io/openprojectx/hive-vanilla:4.2.0-hadoop-3.4.2-jdk21
ghcr.io/openprojectx/hive:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
ghcr.io/openprojectx/hive-standalone-metastore:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
```

## Build Model

Vanilla Dockerfiles install only the original Apache Hive/HMS and Hadoop distributions. Custom Dockerfiles use the vanilla image as `FROM`, copy GCS runtime jars into Hadoop's common library directory, and copy the PostgreSQL JDBC driver into Hive's library directory:

```text
/opt/hadoop/share/hadoop/common/lib
/opt/hive/lib
```

The Dockerfile generator and image tasks live in [image/build.gradle.kts](image/build.gradle.kts).

## Local Development

Use the shared Gradle cache:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :image:tasks --all
```

For local builds, avoid re-downloading large Apache tarballs by placing these files under `/home/coder/Downloads`:

```text
/home/coder/Downloads/apache-hive-3.1.3-bin.tar.gz
/home/coder/Downloads/hadoop-3.4.2.tar.gz
/home/coder/Downloads/apache-hive-4.2.0-bin.tar.gz
/home/coder/Downloads/hive-standalone-metastore-4.2.0-bin.tar.gz
```

Then pass `-PuseLocalTarballs=true`:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerBuildVanillaHive420 \
  -PuseLocalTarballs=true
```

Local tarball mode also rewrites Ubuntu apt sources inside generated Dockerfiles to use the SUSTech mirror.

## Common Tasks

Generate Dockerfiles:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:generateDockerfiles \
  -PuseLocalTarballs=true
```

Build vanilla Hive 4 locally:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerBuildVanillaHive420 \
  -PuseLocalTarballs=true
```

Build custom Hive 4 images after vanilla bases exist locally or in the registry:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerBuildCustomHive4
```

Build and push all custom images:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerPushCustomAll \
  -PimageRegistry=ghcr.io/openprojectx
```

Release custom images with smoke validation:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerReleaseImages \
  -PimageRegistry=ghcr.io/openprojectx
```

This task builds the custom images, runs smoke tests against the just-built local custom image tags, and only then pushes the custom images.

Run the image-only release task:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache release \
  -Prelease.useAutomaticVersion=true \
  -Prelease.releaseVersion=0.1.0 \
  -Prelease.newVersion=0.1.1-SNAPSHOT \
  -PimageRegistry=ghcr.io/openprojectx
```

Run the smoke tests against image tags that already exist locally or in the registry:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest
```

By default, the smoke tests cover these custom image subjects:

```text
hive3
hive4
hive-standalone-metastore-4
```

The smoke tests are split by client version. `:smoke-test:hive3` uses Hive 3.1.3 client dependencies against the Hive 3 image. `:smoke-test:hive4` uses Hive 4.2.0 client dependencies against both the Hive 4 image and the standalone HMS 4 image.

The default image tag includes the current Gradle project version from `gradle.properties`. If that exact tag has not been built or published, Testcontainers will fail while pulling the image. For local development, either build the current custom image first or override the image tag to one that exists.

To run one subject against an existing default tag:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest \
  -Psmoke.subjects=hive-standalone-metastore-4
```

Subject-specific commands:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive3:test \
  -Psmoke.subjects=hive3 \
  -PimageRegistry=ghcr.io/openprojectx

env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive4:test \
  -Psmoke.subjects=hive4 \
  -PimageRegistry=ghcr.io/openprojectx

env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive4:test \
  -Psmoke.subjects=hive-standalone-metastore-4 \
  -PimageRegistry=ghcr.io/openprojectx
```

To test specific image tags:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest \
  -Psmoke.image.hive-standalone-metastore-4=ghcr.io/openprojectx/hive-standalone-metastore:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21 \
  -Psmoke.image.hive4=ghcr.io/openprojectx/hive:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21 \
  -Psmoke.image.hive3=ghcr.io/openprojectx/hive:0.1.0-3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17
```

To build the selected custom images locally before running the smoke test, use `-Psmoke.buildImage=true`. This is the normal local command when the current project version tag has not been published yet:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest \
  -Psmoke.buildImage=true \
  -PuseLocalTarballs=true
```

For only Hive 3:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive3:test \
  -Psmoke.subjects=hive3 \
  -Psmoke.buildImage=true \
  -PimageRegistry=ghcr.io/openprojectx
```

This uses existing vanilla base images from the local Docker cache or registry. Rebuild the selected vanilla bases only when needed:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache smokeTest \
  -Psmoke.buildImage=true \
  -Psmoke.buildVanillaImage=true \
  -PuseLocalTarballs=true
```

The Hive 4 smoke client uses JDK 21 because Hive 4.2.0 client artifacts are Java 21 bytecode. The Hive 3 smoke client stays on Hive 3.1.3 dependencies and JDK 17.

Add `-Dsmoke.containerLogs=true` to any smoke command when debugging container startup.

## PostgreSQL Metastore

Custom images include the PostgreSQL JDBC driver in `/opt/hive/lib`. Vanilla images stay clean and do not include it.

The metastore defaults to embedded Derby. Use PostgreSQL by setting `DB_DRIVER=postgres` and the PostgreSQL connection environment variables:

```bash
docker run --rm \
  -e SERVICE_NAME=metastore \
  -e DB_DRIVER=postgres \
  -e POSTGRES_HOST=postgres \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_DB=metastore \
  -e POSTGRES_USER=hive \
  -e POSTGRES_PASSWORD=hive \
  ghcr.io/openprojectx/hive:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
```

For standalone HMS 4:

```bash
docker run --rm \
  -e DB_DRIVER=postgres \
  -e POSTGRES_HOST=postgres \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_DB=metastore \
  -e POSTGRES_USER=hive \
  -e POSTGRES_PASSWORD=hive \
  ghcr.io/openprojectx/hive-standalone-metastore:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
```

The entrypoint turns those variables into Hive metastore JDO settings:

```text
javax.jdo.option.ConnectionURL=jdbc:postgresql://<POSTGRES_HOST>:<POSTGRES_PORT>/<POSTGRES_DB>
javax.jdo.option.ConnectionDriverName=org.postgresql.Driver
javax.jdo.option.ConnectionUserName=<POSTGRES_USER>
javax.jdo.option.ConnectionPassword=<POSTGRES_PASSWORD>
```

Override the full JDBC URL directly when needed:

```bash
-e METASTORE_DB_CONNECTION_URL='jdbc:postgresql://postgres:5432/metastore?sslmode=require'
```

Schema initialization runs by default. Set `IS_RESUME=true` to skip schema initialization for an already-initialized metastore database.

For external databases, the custom entrypoint passes the JDBC URL, driver, username, and password directly to `schematool`, so first startup initializes or upgrades the schema against the configured database instead of falling back to Derby.

The Hive 3 and Hive 4 smoke tests cover this path with Testcontainers and `postgres:16`:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive3:test \
  --tests 'org.openprojectx.hive.docker.smoke.hive3.Hive3MetastoreSmokeTest.hive3ImageInitializesPostgresSchemaAndAcceptsHive3MetastoreClientRequests' \
  -Psmoke.subjects=hive3 \
  -PimageRegistry=ghcr.io/openprojectx
```

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive4:test \
  -Psmoke.subjects=hive-standalone-metastore-4 \
  -PimageRegistry=ghcr.io/openprojectx
```

Add `-Dsmoke.containerLogs=true` when debugging container startup.

For production, use a managed or persistent PostgreSQL database, pass credentials through your runtime secret mechanism, and keep `IS_RESUME=false` only for first deploys or planned schema upgrades. After the schema exists, set `IS_RESUME=true` for normal restarts.

## Inspect Images

Set the image tag you want to inspect:

```bash
IMAGE=ghcr.io/openprojectx/hive:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
```

Inspect image metadata and layers:

```bash
docker image inspect "$IMAGE"
docker history --no-trunc "$IMAGE"
```

List every jar in the image:

```bash
docker run --rm --entrypoint bash "$IMAGE" -lc '
  find /opt/hadoop/share/hadoop /opt/hive -type f -name "*.jar" | sort
'
```

List installed Hive, Hadoop, and GCS jars:

```bash
docker run --rm --entrypoint bash "$IMAGE" -lc '
  set -e
  echo "Hadoop jars:"
  find /opt/hadoop/share/hadoop -type f -name "*.jar" | sort
  echo
  echo "Hive jars:"
  find /opt/hive -type f -name "*.jar" | sort
  echo
  echo "GCS jars:"
  find /opt/hadoop/share/hadoop /opt/hive -type f -name "*.jar" \
    | sort \
    | grep -E "/(gcs-connector|gcsio|util-hadoop)-"
'
```

Check the key versions in a custom image:

```bash
docker run --rm --entrypoint bash "$IMAGE" -lc '
  set -e
  java -version
  /opt/hadoop/bin/hadoop version | head -n 2
  test -x /opt/hive/bin/hive && /opt/hive/bin/hive --version | head -n 2 || true
  find /opt/hadoop/share/hadoop /opt/hive -type f -name "*.jar" \
    | sort \
    | grep -E "/(hadoop-common|hadoop-aws|aws-java-sdk-bundle|gcs-connector|gcsio|util-hadoop|hive-metastore|postgresql)-"
'
```

Compare vanilla and custom images to confirm that GCS jars are only in the custom layer:

```bash
VANILLA=ghcr.io/openprojectx/hive-vanilla:4.2.0-hadoop-3.4.2-jdk21
CUSTOM=ghcr.io/openprojectx/hive:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21

for image in "$VANILLA" "$CUSTOM"; do
  echo "== $image =="
  docker run --rm --entrypoint bash "$image" -lc '
    find /opt/hadoop/share/hadoop /opt/hive -type f -name "*.jar" \
      | sort \
      | grep -E "/(gcs-connector|gcsio|util-hadoop)-" || true
  '
done
```

For standalone HMS 4, set `IMAGE` to the HMS tag:

```bash
IMAGE=ghcr.io/openprojectx/hive-standalone-metastore:0.1.0-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
```

## GitHub Workflows

### Vanilla Base Images

[.github/workflows/vanilla-base-images.yml](.github/workflows/vanilla-base-images.yml)

Manual only. It builds and publishes vanilla base images. Use it when Hive, Hadoop, JDK, or base image construction changes.

The workflow caches Apache Hive/HMS and Hadoop release tarballs under `.cache/apache-tarballs`, downloads only missing files, and builds with `-PuseLocalTarballs=true`. Vanilla Dockerfiles therefore copy tarballs from the workflow workspace instead of downloading large archives inside Docker builds.

### Custom Images

[.github/workflows/custom-images.yml](.github/workflows/custom-images.yml)

Runs on pushes to `master` and can also be triggered manually. It runs the Gradle `release` task and publishes custom images only.

The release task validates the default smoke subjects before publishing custom images:

```text
<imageRegistry>/hive-standalone-metastore:<project>-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
<imageRegistry>/hive:<project>-4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21
<imageRegistry>/hive:<project>-3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17
```

Those are the same tags produced by the current custom image build in the workflow.

Gradle release commits are skipped to avoid workflow loops:

```yaml
if: ${{ github.event_name != 'push' || !contains(github.event.head_commit.message, '[Gradle Release Plugin]') }}
```

## Caching

Local builds use the Docker daemon cache.

GitHub Actions builds use:

- Gradle dependency cache through `actions/setup-java`
- Docker BuildKit cache with GitHub Actions cache storage

The first CI run after a cache miss is still expensive because the Apache distributions are large. Later runs should reuse Docker layers when the relevant inputs have not changed.
