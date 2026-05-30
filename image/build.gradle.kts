plugins {
    base
}

val gcsRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

val hive3Runtime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

val databaseRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

dependencies {
    gcsRuntime(libs.bundles.gcsRuntime) {
        exclude(group = "org.apache.hadoop")
    }
    hive3Runtime(libs.commonsCollections)
    databaseRuntime(libs.postgresql) {
        isTransitive = false
    }
}

data class HiveTarballImage(
    val id: String,
    val taskSuffix: String,
    val kind: String,
    val hiveVersion: String,
    val hiveUrl: String,
    val vanillaRepository: String,
    val customRepository: String,
    val jdkVersion: Int,
)

val registry = providers.gradleProperty("imageRegistry").orElse("openprojectx")
val useLocalTarballs = providers.gradleProperty("useLocalTarballs").map(String::toBoolean).orElse(false)
val localTarballDir = providers.gradleProperty("localTarballDir").orElse("/home/coder/Downloads")
val hadoopVersion = libs.versions.hadoop.get()
val gcsConnectorVersion = libs.versions.gcsConnector.get()
val postgresqlVersion = libs.versions.postgresql.get()
val hadoopUrl = "https://archive.apache.org/dist/hadoop/core/hadoop-$hadoopVersion/hadoop-$hadoopVersion.tar.gz"

val images = listOf(
    HiveTarballImage(
        id = "hive-3.1.3",
        taskSuffix = "Hive313",
        kind = "full",
        hiveVersion = "3.1.3",
        hiveUrl = "https://archive.apache.org/dist/hive/hive-3.1.3/apache-hive-3.1.3-bin.tar.gz",
        vanillaRepository = "hive-vanilla",
        customRepository = "hive",
        jdkVersion = 17,
    ),
    HiveTarballImage(
        id = "hive-4.2.0",
        taskSuffix = "Hive420",
        kind = "full",
        hiveVersion = "4.2.0",
        hiveUrl = "https://dlcdn.apache.org/hive/hive-4.2.0/apache-hive-4.2.0-bin.tar.gz",
        vanillaRepository = "hive-vanilla",
        customRepository = "hive",
        jdkVersion = 21,
    ),
    HiveTarballImage(
        id = "standalone-metastore-4.2.0",
        taskSuffix = "StandaloneMetastore420",
        kind = "standalone-metastore",
        hiveVersion = "4.2.0",
        hiveUrl = "https://dlcdn.apache.org/hive/hive-standalone-metastore-4.2.0/hive-standalone-metastore-4.2.0-bin.tar.gz",
        vanillaRepository = "hive-standalone-metastore-vanilla",
        customRepository = "hive-standalone-metastore",
        jdkVersion = 21,
    ),
)

val dockerRoot = layout.buildDirectory.dir("docker")
val tarballRoot = dockerRoot.map { it.dir("_tarballs") }

val stageLocalTarballs by tasks.registering(Copy::class) {
    onlyIf { useLocalTarballs.get() }
    into(tarballRoot)
    from(provider { file(localTarballDir.get()).resolve("hadoop-$hadoopVersion.tar.gz") })
    images.forEach { image ->
        from(provider { file(localTarballDir.get()).resolve(artifactName(image)) })
    }
}

val stageGcsDependencies by tasks.registering(Sync::class) {
    into(dockerRoot.map { it.dir("_shared/gcs-libs") })
    from(gcsRuntime)
}

val stageHive3Dependencies by tasks.registering(Sync::class) {
    into(dockerRoot.map { it.dir("_shared/hive3-libs") })
    from(hive3Runtime)
}

val stageDatabaseDependencies by tasks.registering(Sync::class) {
    into(dockerRoot.map { it.dir("_shared/database-libs") })
    from(databaseRuntime)
}

fun artifactName(image: HiveTarballImage): String =
    if (image.kind == "standalone-metastore") {
        "hive-standalone-metastore-${image.hiveVersion}-bin.tar.gz"
    } else {
        "apache-hive-${image.hiveVersion}-bin.tar.gz"
    }

fun extractedHiveDir(image: HiveTarballImage): String =
    if (image.kind == "standalone-metastore") {
        "apache-hive-metastore-${image.hiveVersion}-bin"
    } else {
        "apache-hive-${image.hiveVersion}-bin"
    }

fun dockerTagSuffix(image: HiveTarballImage, custom: Boolean): String =
    buildString {
        if (custom) {
            append(project.version)
            append("-")
        }
        append(image.hiveVersion)
        append("-hadoop-")
        append(hadoopVersion)
        if (custom) {
            append("-gcs-")
            append(gcsConnectorVersion)
        }
        append("-jdk")
        append(image.jdkVersion)
    }

fun dockerImage(image: HiveTarballImage, custom: Boolean): String {
    val repository = if (custom) image.customRepository else image.vanillaRepository
    return "${registry.get()}/$repository:${dockerTagSuffix(image, custom)}"
}

fun String.cleanDockerfile(): String =
    lineSequence()
        .map { it.trimStart() }
        .joinToString("\n")
        .trim()

fun vanillaDockerfile(image: HiveTarballImage): String {
    val packagingDir = if (image.kind == "standalone-metastore") "standalone-metastore" else "full"
    val hiveTarball = artifactName(image)
    val hiveDir = extractedHiveDir(image)
    val hadoopExcludes = if (image.kind == "standalone-metastore") {
        """
              --exclude="hadoop-${'$'}HADOOP_VERSION/lib/native" \
              --exclude="hadoop-${'$'}HADOOP_VERSION/share/doc" \
              --exclude="hadoop-${'$'}HADOOP_VERSION/share/hadoop/client" \
              --exclude="hadoop-${'$'}HADOOP_VERSION/share/hadoop/yarn/*" \
              --exclude="*/jdiff" \
              --exclude="*/sources" \
              --exclude="*tests.jar" \
              --exclude="*/webapps" \
        """.trimIndent()
    } else {
        """
              --exclude="hadoop-${'$'}HADOOP_VERSION/share/doc" \
              --exclude="*/jdiff" \
              --exclude="*/sources" \
              --exclude="*tests.jar" \
              --exclude="*/webapps" \
        """.trimIndent()
    }
    val entryCommand = if (image.kind == "standalone-metastore") {
        """ENTRYPOINT ["sh", "-c", "/entrypoint.sh"]"""
    } else {
        """ENTRYPOINT ["sh", "-c", "/entrypoint.sh"]"""
    }
    val aptMirrorSetup = if (useLocalTarballs.get()) {
        """
            sed -i "s@http://.*archive.ubuntu.com@https://mirrors.sustech.edu.cn@g" /etc/apt/sources.list; \
            sed -i "s@http://.*security.ubuntu.com@https://mirrors.sustech.edu.cn@g" /etc/apt/sources.list; \
        """.trimIndent()
    } else {
        ""
    }

    val fetchHiveTarballs = if (useLocalTarballs.get()) {
        """
        COPY _tarballs/hadoop-$hadoopVersion.tar.gz /opt/hadoop-$hadoopVersion.tar.gz
        COPY _tarballs/$hiveTarball /opt/$hiveTarball
        """.trimIndent()
    } else {
        """
        ARG HADOOP_URL=$hadoopUrl
        ARG HIVE_URL=${image.hiveUrl}
        RUN set -eux; \
            apt-get update; \
            apt-get install -y --no-install-recommends ca-certificates curl tar gzip; \
            rm -rf /var/lib/apt/lists/*; \
            curl -fL "${'$'}HADOOP_URL" -o /opt/hadoop-${'$'}HADOOP_VERSION.tar.gz; \
            curl -fL "${'$'}HIVE_URL" -o /opt/$hiveTarball
        """.trimIndent()
    }

    return """
        FROM ubuntu:22.04 AS fetch
        ARG HADOOP_VERSION=$hadoopVersion
        $fetchHiveTarballs
        RUN set -eux; \
            tar -xz \
        $hadoopExcludes
              -f /opt/hadoop-${'$'}HADOOP_VERSION.tar.gz \
              -C /opt; \
            tar -xzf /opt/$hiveTarball -C /opt

        FROM eclipse-temurin:${image.jdkVersion}-jre-jammy AS run
        ARG UID=1000
        ARG HADOOP_VERSION=$hadoopVersion
        ARG HIVE_VERSION=${image.hiveVersion}
        RUN set -eux; \
        $aptMirrorSetup
            apt-get update; \
            apt-get install -y --no-install-recommends procps gettext-base; \
            rm -rf /var/lib/apt/lists/*; \
            useradd --no-create-home -s /usr/sbin/nologin --uid "${'$'}UID" hive
        ENV HADOOP_HOME=/opt/hadoop \
            HIVE_HOME=/opt/hive \
            HIVE_VER=${'$'}HIVE_VERSION \
            HIVE_CONF_DIR=/opt/hive/conf
        ENV PATH=/opt/hive/bin:/opt/hadoop/bin:${'$'}PATH
        COPY --from=fetch --chown=hive:hive /opt/hadoop-${'$'}HADOOP_VERSION ${'$'}HADOOP_HOME
        COPY --from=fetch --chown=hive:hive /opt/$hiveDir ${'$'}HIVE_HOME
        COPY --chown=hive:hive $packagingDir/entrypoint.sh /entrypoint.sh
        COPY --chown=hive:hive $packagingDir/conf ${'$'}HIVE_HOME/conf
        RUN set -eux; \
            chmod +x /entrypoint.sh; \
            mkdir -p "${'$'}HIVE_HOME/data/warehouse" "${'$'}HIVE_HOME/scratch" /home/hive/.beeline; \
            chown -R hive:hive "${'$'}HIVE_HOME/data" "${'$'}HIVE_HOME/scratch" /home/hive/.beeline
        USER hive
        WORKDIR ${'$'}HIVE_HOME
        EXPOSE 10000 10002 9083
        $entryCommand
    """.cleanDockerfile()
}

fun customDockerfile(image: HiveTarballImage): String {
    val packagingDir = if (image.kind == "standalone-metastore") "standalone-metastore" else "full"
    val hive3Compatibility = if (image.hiveVersion == "3.1.3") {
        """
            COPY _shared/hive3-libs/ /tmp/hive3-libs/
            RUN set -eux; \
                cp /tmp/hive3-libs/*.jar /opt/hive/lib/; \
                rm -rf /tmp/hive3-libs; \
                chown -R hive:hive /opt/hive
        """.trimIndent()
    } else {
        ""
    }
    return """
        FROM ${dockerImage(image, custom = false)}

        USER root
        COPY --chown=hive:hive $packagingDir/entrypoint.sh /entrypoint.sh
        COPY --chown=hive:hive $packagingDir/conf ${'$'}HIVE_HOME/conf
        COPY _shared/gcs-libs/ /tmp/gcs-libs/
        COPY _shared/database-libs/ /tmp/database-libs/
        RUN set -eux; \
            chmod +x /entrypoint.sh; \
            mkdir -p /opt/hadoop/share/hadoop/common/lib; \
            cp /tmp/gcs-libs/*.jar /opt/hadoop/share/hadoop/common/lib/; \
            cp /tmp/database-libs/*.jar /opt/hive/lib/; \
            rm -rf /tmp/gcs-libs; \
            rm -rf /tmp/database-libs; \
            chown -R hive:hive /opt/hadoop /opt/hive
        $hive3Compatibility
        USER hive
    """.cleanDockerfile()
}

val generateDockerfiles by tasks.registering {
    outputs.dir(dockerRoot)
    inputs.property("useLocalTarballs", useLocalTarballs)
    inputs.property("imageRegistry", registry)
    inputs.property("artifactVersion", provider { project.version.toString() })
    inputs.property("hadoopVersion", hadoopVersion)
    inputs.property("gcsConnectorVersion", gcsConnectorVersion)
    inputs.property("postgresqlVersion", postgresqlVersion)

    doLast {
        val root = dockerRoot.get().asFile
        images.forEach { image ->
            val imageDir = root.resolve(image.taskSuffix)
            imageDir.mkdirs()
            imageDir.resolve("Dockerfile.vanilla").writeText(vanillaDockerfile(image))
            imageDir.resolve("Dockerfile.custom").writeText(customDockerfile(image))
        }
    }
}

val stageDockerAssets by tasks.registering(Copy::class) {
    into(dockerRoot)
    into("full") {
        from(layout.projectDirectory.dir("src/docker/full"))
    }
    into("standalone-metastore") {
        from(layout.projectDirectory.dir("src/docker/standalone-metastore"))
    }
}

fun registerDockerBuildTask(
    name: String,
    dockerfileName: String,
    imageTag: Provider<String>,
    needsTarballs: Boolean,
    cacheScope: String,
    extraDependsOn: Any? = null,
) {
    tasks.register<Exec>(name) {
        dependsOn(generateDockerfiles, stageDockerAssets)
        if (needsTarballs && useLocalTarballs.get()) {
            dependsOn(stageLocalTarballs)
        }
        if (extraDependsOn != null) {
            dependsOn(extraDependsOn)
        }
        workingDir = dockerRoot.get().asFile
        val dockerfile = dockerRoot.get().file(dockerfileName).asFile.absolutePath
        if (System.getenv("GITHUB_ACTIONS").equals("true", ignoreCase = true)) {
            commandLine(
                "docker",
                "buildx",
                "build",
                "--load",
                "--cache-from",
                "type=gha,scope=$cacheScope",
                "--cache-to",
                "type=gha,mode=max,scope=$cacheScope",
                "-f",
                dockerfile,
                "-t",
                imageTag.get(),
                ".",
            )
        } else {
            commandLine(
                "docker",
                "build",
                "-f",
                dockerfile,
                "-t",
                imageTag.get(),
                ".",
            )
        }
    }
}

fun registerDockerPushTask(
    name: String,
    imageTag: Provider<String>,
    buildTaskName: String,
) {
    tasks.register<Exec>(name) {
        dependsOn(buildTaskName)
        commandLine("docker", "push", imageTag.get())
    }
}

images.forEach { image ->
    val vanillaBuildTask = "dockerBuildVanilla${image.taskSuffix}"
    val customBuildTask = "dockerBuildCustom${image.taskSuffix}"
    registerDockerBuildTask(
        name = vanillaBuildTask,
        dockerfileName = "${image.taskSuffix}/Dockerfile.vanilla",
        imageTag = provider { dockerImage(image, custom = false) },
        needsTarballs = true,
        cacheScope = "vanilla-${image.taskSuffix}",
    )
    registerDockerBuildTask(
        name = customBuildTask,
        dockerfileName = "${image.taskSuffix}/Dockerfile.custom",
        imageTag = provider { dockerImage(image, custom = true) },
        needsTarballs = false,
        cacheScope = "custom-${image.taskSuffix}",
        extraDependsOn = if (image.hiveVersion == "3.1.3") {
            listOf(stageGcsDependencies, stageDatabaseDependencies, stageHive3Dependencies)
        } else {
            listOf(stageGcsDependencies, stageDatabaseDependencies)
        },
    )
    tasks.named(customBuildTask) {
        mustRunAfter(vanillaBuildTask)
    }
    registerDockerPushTask(
        name = "dockerPushVanilla${image.taskSuffix}",
        imageTag = provider { dockerImage(image, custom = false) },
        buildTaskName = vanillaBuildTask,
    )
    registerDockerPushTask(
        name = "dockerPushCustom${image.taskSuffix}",
        imageTag = provider { dockerImage(image, custom = true) },
        buildTaskName = customBuildTask,
    )
    tasks.named("dockerPushCustom${image.taskSuffix}") {
        mustRunAfter("dockerPushVanilla${image.taskSuffix}")
        mustRunAfter(":smoke-test:test")
    }
}

tasks.register("dockerBuildVanillaAll") {
    dependsOn(images.map { "dockerBuildVanilla${it.taskSuffix}" })
}

tasks.register("dockerBuildCustomAll") {
    dependsOn(images.map { "dockerBuildCustom${it.taskSuffix}" })
}

tasks.register("dockerBuildCustomHive4") {
    dependsOn("dockerBuildCustomHive420", "dockerBuildCustomStandaloneMetastore420")
}

tasks.register("dockerPushVanillaAll") {
    dependsOn(images.map { "dockerPushVanilla${it.taskSuffix}" })
}

tasks.register("dockerPushCustomAll") {
    dependsOn(images.map { "dockerPushCustom${it.taskSuffix}" })
}

tasks.register("dockerPushCustomHive4") {
    dependsOn("dockerPushCustomHive420", "dockerPushCustomStandaloneMetastore420")
}

tasks.register("dockerReleaseImages") {
    dependsOn("dockerBuildCustomAll")
    dependsOn(":smoke-test:test")
    dependsOn("dockerPushCustomAll")
}

tasks.named("dockerPushCustomAll") {
    mustRunAfter(":smoke-test:test")
}

gradle.projectsEvaluated {
    project(":smoke-test").tasks.named("test") {
        mustRunAfter(":image:dockerBuildCustomAll")
    }
}
