# hive-docker

Docker image build project for Apache Hive and Hive Standalone Metastore with Apache Hadoop 3.4.2.

The project builds two image layers:

- **Vanilla base images**: Apache Hive/HMS plus Hadoop only. These do not include this project's release version in the tag.
- **Custom images**: built from the vanilla base images and add S3A, GCS connector, and PostgreSQL/MySQL JDBC runtime dependencies. These include the Gradle project release version in the tag.

Releases publish Docker images and the `hive-docker-testcontainers` helper jar.

## Images

| Image | Purpose | Tag format |
| --- | --- | --- |
| `hive-vanilla` | Base Hive image | `<hive>-hadoop-<hadoop>-jdk<jdk>` |
| `hive-standalone-metastore-vanilla` | Base standalone HMS image | `<hive>-hadoop-<hadoop>-jdk<jdk>` |
| `hive` | Custom Hive image with S3A, GCS, and PostgreSQL/MySQL JDBC libraries | `<hive>-hadoop-<hadoop>-gcs-<gcs>-jdk<jdk>-<project>` |
| `hive-standalone-metastore` | Custom standalone HMS image with S3A, GCS, and PostgreSQL/MySQL JDBC libraries | `<hive>-hadoop-<hadoop>-gcs-<gcs>-jdk<jdk>-<project>` |

Every build also tags the same image with the first 8 characters of the Git commit appended to the normal tag, for example `<normal-tag>-1a2b3c4d`. Use the normal tag for stable version selection and the commit tag for traceability.

Current versions are managed in [gradle/libs.versions.toml](gradle/libs.versions.toml):

- Hadoop: `3.4.2`
- GCS connector: `4.0.4`
- PostgreSQL JDBC: `42.7.4`
- MySQL Connector/J: `8.4.0`
- Hive: `3.1.3`, `4.2.0`
- Standalone metastore: `4.2.0`
- JDK: `17` for Hive 3.1.3, `21` for Hive 4.2.0/HMS 4.2.0

Example tags:

```text
ghcr.io/openprojectx/hive-vanilla:4.2.0-hadoop-3.4.2-jdk21
ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
ghcr.io/openprojectx/hive-standalone-metastore:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

## Build Model

Vanilla Dockerfiles install only the original Apache Hive/HMS and Hadoop distributions. Custom Dockerfiles use the vanilla image as `FROM`, copy S3A and GCS runtime jars into Hadoop's common library directory, and copy PostgreSQL and MySQL JDBC drivers into Hive's library directory:

```text
/opt/hadoop/share/hadoop/common/lib
/opt/hive/lib
```

The Dockerfile generator and image tasks live in [image/build.gradle.kts](image/build.gradle.kts).

Custom image jar placement is config-driven in Gradle. By default, Hive 3 removes known old Hive-side dependency families before installing the GCS-compatible copies into `/opt/hive/lib`; this currently covers Guava, failureaccess, listenablefuture, and Disruptor. This avoids duplicate classes from older Hive 3 jars.

Build-time jar conflict options:

| Gradle property | Default | Purpose |
| --- | --- | --- |
| `image.jarConflictStrategy` | `remove` | Use `remove` to delete configured conflicting target jars before copying replacements, or `keep` to only add jars. |
| `image.priorityJarDir` | unset | Directory of user-provided `*.jar` files to bake into `/opt/hive/lib` in the custom image. |
| `image.priorityJarRemovePatterns` | unset | Comma-separated target jar globs to remove from `/opt/hive/lib` before copying `image.priorityJarDir` jars. |

Example: build a custom Hive 3 image with a site-provided replacement jar:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache \
  :image:dockerBuildCustomHive313 \
  -PimageRegistry=ghcr.io/openprojectx \
  -Pimage.priorityJarDir="$PWD/priority-jars" \
  -Pimage.priorityJarRemovePatterns='example-lib-*.jar'
```

Use `image.priorityJarDir` for deterministic image builds. The runtime `/tmp/ext-jars` mount is still available for quick experiments, but it does not remove older conflicting jars.

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

Run the full release through the Gradle release plugin. By default this builds and pushes custom images first, then publishes the Testcontainers helper jar:

```bash
env OSSRH_USERNAME=<user> OSSRH_PASSWORD=<password> \
  SIGNING_KEY_FILE=/path/to/signing-key.asc SIGNING_KEY_PASSWORD=<password> \
  GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache release \
  -Prelease.useAutomaticVersion=true \
  -Prelease.releaseVersion=0.1.0 \
  -Prelease.newVersion=0.1.1-SNAPSHOT \
  -PimageRegistry=ghcr.io/openprojectx
```

Focused manual releases are available with `-Prelease.kind=images` or `-Prelease.kind=jar`.

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache release \
  -Prelease.kind=images \
  -Prelease.useAutomaticVersion=true \
  -Prelease.releaseVersion=0.1.0 \
  -Prelease.newVersion=0.1.1-SNAPSHOT \
  -PimageRegistry=ghcr.io/openprojectx
```

## Testcontainers Module

The `:testcontainers` module publishes the `hive-docker-testcontainers` helper jar and also owns integration-level storage smoke tests for Spark plus Iceberg.

Run the Testcontainers module tests:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :testcontainers:test
```

By default, this includes the comprehensive Spark/Iceberg object-store smoke tests and first builds the current custom Hive 3 image with `:image:dockerBuildCustomHive313`. The tests start that image through `HiveMetastoreContainer` as HMS with `SERVICE_NAME=metastore`. It does not build vanilla images; the matching vanilla base image must already exist locally or be pullable by Docker.

To skip those Docker-backed tests for a quick local run:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :testcontainers:test \
  -Ptestcontainers.skipStorageSmoke=true
```

To use an already-built HMS image without running the image build task:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :testcontainers:test \
  -Ptestcontainers.hmsImage=ghcr.io/openprojectx/hive:3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17-0.1.2-SNAPSHOT
```

Those tests start these Testcontainers dependencies:

```text
localstack/localstack:4.14.0
fsouza/fake-gcs-server:1.54
```

The S3 and GCS tests start `HiveMetastoreContainer`, configure Spark Iceberg with a Hive catalog through the HMS thrift URI, write and read Spark rows, then assert that Parquet data files exist in the backing object store.

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
  -Psmoke.image.hive-standalone-metastore-4=ghcr.io/openprojectx/hive-standalone-metastore:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0 \
  -Psmoke.image.hive4=ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0 \
  -Psmoke.image.hive3=ghcr.io/openprojectx/hive:3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17-0.1.0
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

## Docker Usage

The three custom runtime images use the same base layout and the same metastore configuration model:

| Subject | Image | JDK | Main use | Startup behavior |
| --- | --- | --- | --- | --- |
| Hive 3 | `ghcr.io/openprojectx/hive:3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17-<project>` | 17 | Hive 3 metastore compatibility | Full Hive image. Set `SERVICE_NAME=metastore` for HMS. |
| Hive 4 | `ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-<project>` | 21 | Hive 4 services | Full Hive image. Set `SERVICE_NAME=metastore`, `hiveserver2`, `llap`, or `tezam`. |
| Standalone HMS 4 | `ghcr.io/openprojectx/hive-standalone-metastore:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-<project>` | 21 | HMS-only runtime | Always starts the metastore service. `SERVICE_NAME` is not required. |

All custom images include:

- Hadoop under `/opt/hadoop`
- Hive or standalone HMS under `/opt/hive`
- generated config under `/opt/hive/conf`
- GCS runtime jars under `/opt/hadoop/share/hadoop/common/lib`
- PostgreSQL and MySQL JDBC drivers under `/opt/hive/lib`

Shared runtime inputs:

| Env or mount | Default | Applies to | Purpose |
| --- | --- | --- | --- |
| `HIVE_CUSTOM_CONF_DIR` | unset | all custom images | Directory of custom config files to symlink into `/opt/hive/conf`. |
| `/tmp/ext-jars` | unset | all custom images | Mount extra `*.jar` files; entrypoint copies them into `/opt/hive/lib` before startup. This is a runtime convenience path, not the preferred conflict-replacement path. |
| `HIVE_WAREHOUSE_PATH` | `/opt/hive/data/warehouse` | all custom images | Warehouse path used in generated config. |
| `DB_DRIVER` | `derby` | all custom images | Use `derby`, `postgres`, `postgresql`, or `mysql`. |
| `IS_RESUME` | `false` | all custom images | Set `true` to skip `schematool` schema initialization on restart. |
| `VERBOSE` | unset | all custom images | Set `true` to pass verbose mode to schema initialization where supported. |
| `HIVE_LOG_LEVEL` | `INFO` | all custom images | Root Hive log4j2 level, for example `DEBUG` while troubleshooting. |
| `HIVE_PERF_LOG_LEVEL` | `INFO` | all custom images | Hive `PerfLogger` log level. |
| `HIVE_ROOT_LOGGER` | `stdout` | all custom images | Hive root log4j2 appender reference. |
| `HIVE_LOG4J2_CONFIGURATION_FILE` | unset | all custom images | Full path to a mounted custom log4j2 properties file. |
| `SERVICE_OPTS` | unset | all custom images | Extra JVM options appended to `HADOOP_CLIENT_OPTS`. |
| `METASTORE_PORT` | `9083` | HMS services | Metastore thrift port inside the container. |

PostgreSQL envs are also shared by all custom images:

| Env | Default |
| --- | --- |
| `POSTGRES_HOST` | `postgres` |
| `POSTGRES_PORT` | `5432` |
| `POSTGRES_DB` | `metastore` |
| `POSTGRES_USER` | `hive` |
| `POSTGRES_PASSWORD` | `hive` |
| `METASTORE_DB_CONNECTION_URL` | derived from the `POSTGRES_*` values |
| `METASTORE_DB_CONNECTION_DRIVER` | `org.postgresql.Driver` |
| `METASTORE_DB_CONNECTION_USER_NAME` | derived from `POSTGRES_USER` |
| `METASTORE_DB_CONNECTION_PASSWORD` | derived from `POSTGRES_PASSWORD` |

MySQL envs are also shared by all custom images:

| Env | Default |
| --- | --- |
| `MYSQL_HOST` | `mysql` |
| `MYSQL_PORT` | `3306` |
| `MYSQL_DB` | `metastore` |
| `MYSQL_USER` | `hive` |
| `MYSQL_PASSWORD` | `hive` |
| `METASTORE_DB_CONNECTION_URL` | derived from the `MYSQL_*` values |
| `METASTORE_DB_CONNECTION_DRIVER` | `com.mysql.cj.jdbc.Driver` |
| `METASTORE_DB_CONNECTION_USER_NAME` | derived from `MYSQL_USER` |
| `METASTORE_DB_CONNECTION_PASSWORD` | derived from `MYSQL_PASSWORD` |

The metastore database envs are intentionally the same across all three images. The full Hive images render `hive-site.xml` and select a service with `SERVICE_NAME`; the standalone HMS image renders `metastore-site.xml` and starts HMS directly.

Run Hive 3 as HMS with embedded Derby:

```bash
docker run --rm -p 9083:9083 \
  -e SERVICE_NAME=metastore \
  ghcr.io/openprojectx/hive:3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17-0.1.0
```

Run Hive 4 as HMS with embedded Derby:

```bash
docker run --rm -p 9083:9083 \
  -e SERVICE_NAME=metastore \
  ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

Run standalone HMS 4 with embedded Derby:

```bash
docker run --rm -p 9083:9083 \
  ghcr.io/openprojectx/hive-standalone-metastore:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

For persistent local Derby state, mount a writable data directory:

```bash
docker run --rm -p 9083:9083 \
  -v "$PWD/.hive-data:/opt/hive/data" \
  -e SERVICE_NAME=metastore \
  ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

For production HMS usage, prefer an external PostgreSQL or MySQL database over embedded Derby and set `IS_RESUME=true` after the schema exists.

Run HiveServer2 from the full Hive 4 image:

```bash
docker run --rm -p 10000:10000 \
  -e SERVICE_NAME=hiveserver2 \
  ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

Mount custom config files:

```bash
docker run --rm -p 9083:9083 \
  -e SERVICE_NAME=metastore \
  -e HIVE_CUSTOM_CONF_DIR=/conf \
  -v "$PWD/conf:/conf:ro" \
  ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

Enable verbose Hive logging without mounting a full config directory:

```bash
docker run --rm -p 9083:9083 \
  -e SERVICE_NAME=metastore \
  -e HIVE_LOG_LEVEL=DEBUG \
  -e HIVE_PERF_LOG_LEVEL=DEBUG \
  ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

Use a custom log4j2 file when you need package-specific loggers:

```bash
docker run --rm -p 9083:9083 \
  -e SERVICE_NAME=metastore \
  -e HIVE_LOG4J2_CONFIGURATION_FILE=/conf/hive-log4j2-debug.properties \
  -v "$PWD/conf:/conf:ro" \
  ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

Mount extra jars, for example a site-specific JDBC or filesystem dependency:

```bash
docker run --rm -p 9083:9083 \
  -e SERVICE_NAME=metastore \
  -v "$PWD/ext-jars:/tmp/ext-jars:ro" \
  ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

If Spark creates external tables with `s3a://` or `gs://` locations through HMS, the metastore usually stores the location string and Spark performs the object-store IO. Keep the object-store client libraries and credentials available in Spark as well as in HMS for any HMS-side validation, schema tooling, or service behavior that touches paths.

## Testcontainers Helper

The `:testcontainers` module publishes `org.openprojectx.hive.docker.core:hive-docker-testcontainers:<version>`. It provides image tag helpers and preconfigured HMS containers with the same tag format as the Docker build.

Gradle dependency example:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/openprojectx/hive-docker")
        credentials {
            username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).get()
            password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).get()
        }
    }
}

dependencies {
    testImplementation("org.openprojectx.hive.docker.core:hive-docker-testcontainers:0.1.0")
}
```

Usage example:

```java
import org.openprojectx.hive.docker.testcontainers.HiveMetastoreContainer;

try (HiveMetastoreContainer metastore = HiveMetastoreContainer.standaloneMetastore4("0.1.0")) {
    metastore.start();
    String thriftUri = metastore.getThriftUri();
}
```

PostgreSQL-backed HMS example:

```java
try (HiveMetastoreContainer metastore = HiveMetastoreContainer.hive4("0.1.0")
    .withPostgres("postgres", 5432, "metastore", "hive", "hive")) {
    metastore.start();
}

try (HiveMetastoreContainer metastore = HiveMetastoreContainer.standaloneMetastore4("0.1.0")
    .withMysql("mysql", 3306, "metastore", "hive", "hive")) {
    metastore.start();
}
```

## External Metastore Databases

Custom images include PostgreSQL and MySQL JDBC drivers in `/opt/hive/lib`. Vanilla images stay clean and do not include them.

The metastore defaults to embedded Derby. Use PostgreSQL by setting `DB_DRIVER=postgres` and the PostgreSQL connection environment variables:

Start a local PostgreSQL database for Docker testing:

```bash
docker network create hive-smoke

docker run -d --name hive-postgres --network hive-smoke \
  -e POSTGRES_DB=metastore \
  -e POSTGRES_USER=hive \
  -e POSTGRES_PASSWORD=hive \
  postgres:16
```

Run Hive 3 HMS against that PostgreSQL container:

```bash
docker run --rm --network hive-smoke -p 9083:9083 \
  -e SERVICE_NAME=metastore \
  -e DB_DRIVER=postgres \
  -e POSTGRES_HOST=hive-postgres \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_DB=metastore \
  -e POSTGRES_USER=hive \
  -e POSTGRES_PASSWORD=hive \
  ghcr.io/openprojectx/hive:3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17-0.1.0
```

Run Hive 4 HMS against PostgreSQL:

```bash
docker run --rm --network hive-smoke -p 9083:9083 \
  -e SERVICE_NAME=metastore \
  -e DB_DRIVER=postgres \
  -e POSTGRES_HOST=hive-postgres \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_DB=metastore \
  -e POSTGRES_USER=hive \
  -e POSTGRES_PASSWORD=hive \
  ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

Run standalone HMS 4 against PostgreSQL:

```bash
docker run --rm --network hive-smoke -p 9083:9083 \
  -e DB_DRIVER=postgres \
  -e POSTGRES_HOST=hive-postgres \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_DB=metastore \
  -e POSTGRES_USER=hive \
  -e POSTGRES_PASSWORD=hive \
  ghcr.io/openprojectx/hive-standalone-metastore:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

Use MySQL by setting `DB_DRIVER=mysql` and the MySQL connection environment variables:

```bash
docker run -d --name hive-mysql --network hive-smoke \
  -e MYSQL_DATABASE=metastore \
  -e MYSQL_USER=hive \
  -e MYSQL_PASSWORD=hive \
  -e MYSQL_ROOT_PASSWORD=root \
  mysql:8.0.44-bookworm
```

Run standalone HMS 4 against MySQL:

```bash
docker run --rm --network hive-smoke -p 9083:9083 \
  -e DB_DRIVER=mysql \
  -e MYSQL_HOST=hive-mysql \
  -e MYSQL_PORT=3306 \
  -e MYSQL_DB=metastore \
  -e MYSQL_USER=hive \
  -e MYSQL_PASSWORD=hive \
  ghcr.io/openprojectx/hive-standalone-metastore:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

The entrypoint turns those variables into Hive metastore JDO settings:

```text
javax.jdo.option.ConnectionURL=jdbc:postgresql://<POSTGRES_HOST>:<POSTGRES_PORT>/<POSTGRES_DB>
javax.jdo.option.ConnectionDriverName=org.postgresql.Driver
javax.jdo.option.ConnectionUserName=<POSTGRES_USER>
javax.jdo.option.ConnectionPassword=<POSTGRES_PASSWORD>
```

For MySQL, the derived settings are:

```text
javax.jdo.option.ConnectionURL=jdbc:mysql://<MYSQL_HOST>:<MYSQL_PORT>/<MYSQL_DB>?useSSL=false&allowPublicKeyRetrieval=true
javax.jdo.option.ConnectionDriverName=com.mysql.cj.jdbc.Driver
javax.jdo.option.ConnectionUserName=<MYSQL_USER>
javax.jdo.option.ConnectionPassword=<MYSQL_PASSWORD>
```

Override the full JDBC URL directly when needed:

```bash
-e METASTORE_DB_CONNECTION_URL='jdbc:postgresql://postgres:5432/metastore?sslmode=require'
```

Schema initialization runs by default. Set `IS_RESUME=true` to skip schema initialization for an already-initialized metastore database.

For external databases, the custom entrypoint passes the JDBC URL, driver, username, and password directly to `schematool`, so first startup initializes or upgrades the schema against the configured database instead of falling back to Derby.

The Hive 3 and Hive 4 smoke tests cover this path with Testcontainers, `postgres:16`, and `mysql:8.0.44-bookworm`:

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive3:test \
  --tests 'org.openprojectx.hive.docker.smoke.hive3.Hive3MetastoreSmokeTest.hive3ImageInitializesPostgresSchemaAndAcceptsHive3MetastoreClientRequests' \
  -Psmoke.subjects=hive3 \
  -PimageRegistry=ghcr.io/openprojectx
```

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive3:test \
  --tests 'org.openprojectx.hive.docker.smoke.hive3.Hive3MetastoreSmokeTest.hive3ImageInitializesMysqlSchemaAndAcceptsHive3MetastoreClientRequests' \
  -Psmoke.subjects=hive3 \
  -PimageRegistry=ghcr.io/openprojectx
```

```bash
env GRADLE_USER_HOME=/data/.gradle ./gradlew --no-configuration-cache :smoke-test:hive4:test \
  -Psmoke.subjects=hive-standalone-metastore-4 \
  -PimageRegistry=ghcr.io/openprojectx
```

Add `-Dsmoke.containerLogs=true` when debugging container startup.

For production, use a managed or persistent PostgreSQL or MySQL database, pass credentials through your runtime secret mechanism, and keep `IS_RESUME=false` only for first deploys or planned schema upgrades. After the schema exists, set `IS_RESUME=true` for normal restarts.

## Inspect Images

Set the image tag you want to inspect:

```bash
IMAGE=ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
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
    | grep -E "/(hadoop-common|hadoop-aws|aws-java-sdk-bundle|gcs-connector|gcsio|util-hadoop|hive-metastore|postgresql|mysql-connector-j)-"
'
```

Compare vanilla and custom images to confirm that GCS jars are only in the custom layer:

```bash
VANILLA=ghcr.io/openprojectx/hive-vanilla:4.2.0-hadoop-3.4.2-jdk21
CUSTOM=ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0

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
IMAGE=ghcr.io/openprojectx/hive-standalone-metastore:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.0
```

## Clean Local Snapshot Images

Local development can leave multiple `SNAPSHOT` image tags in the Docker cache. Docker lists images newest first, so this keeps the newest local snapshot tag per image repository and removes older snapshot tags.

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

If an old image is still referenced by a stopped container, remove stopped containers first:

```bash
docker container prune
```

## GitHub Workflows

### Vanilla Base Images

[.github/workflows/vanilla-base-images.yml](.github/workflows/vanilla-base-images.yml)

Manual only. It builds and publishes vanilla base images. Use it when Hive, Hadoop, JDK, or base image construction changes.

The workflow caches Apache Hive/HMS and Hadoop release tarballs under `.cache/apache-tarballs`, downloads only missing files, and builds with `-PuseLocalTarballs=true`. Vanilla Dockerfiles therefore copy tarballs from the workflow workspace instead of downloading large archives inside Docker builds.

### Custom Images

[.github/workflows/custom-images.yml](.github/workflows/custom-images.yml)

Runs on pushes to `master` and can also be triggered manually. It runs the Gradle `release` task with `-Prelease.kind=all`, publishing custom images first and then the `hive-docker-testcontainers` jar.

The release task validates the default smoke subjects before publishing custom images:

```text
<imageRegistry>/hive-standalone-metastore:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-<project>
<imageRegistry>/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-<project>
<imageRegistry>/hive:3.1.3-hadoop-3.4.2-gcs-4.0.4-jdk17-<project>
```

Those are the same tags produced by the current custom image build in the workflow.

Each published image is also pushed with a commit trace tag:

```text
<normal-tag>-<git-sha8>
```

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
