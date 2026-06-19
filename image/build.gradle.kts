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
    gcsRuntime(libs.hadoopAws) {
        exclude(group = "org.apache.hadoop")
    }
    hive3Runtime(libs.commonsCollections)
    databaseRuntime(libs.postgresql) {
        isTransitive = false
    }
    databaseRuntime(libs.mysqlConnector) {
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
val jarConflictStrategy = providers.gradleProperty("image.jarConflictStrategy").orElse("remove")
val priorityJarDir = providers.gradleProperty("image.priorityJarDir")
val priorityJarRemovePatterns = providers.gradleProperty("image.priorityJarRemovePatterns")
    .map { value ->
        value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
    .orElse(emptyList())
val hadoopVersion = libs.versions.hadoop.get()
val gcsConnectorVersion = libs.versions.gcsConnector.get()
val postgresqlVersion = libs.versions.postgresql.get()
val mysqlConnectorVersion = libs.versions.mysqlConnector.get()
val hadoopUrl = "https://archive.apache.org/dist/hadoop/core/hadoop-$hadoopVersion/hadoop-$hadoopVersion.tar.gz"
val gitCommitShort = providers.environmentVariable("GITHUB_SHA")
    .map { it.take(8) }
    .orElse(
        providers.exec {
            commandLine("git", "rev-parse", "--short=8", "HEAD")
        }.standardOutput.asText.map { it.trim().take(8) }
    )

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

val stagePriorityJarDependencies by tasks.registering(Sync::class) {
    into(dockerRoot.map { it.dir("_shared/priority-libs") })
    if (priorityJarDir.isPresent) {
        from(provider { file(priorityJarDir.get()) }) {
            include("*.jar")
        }
    }
    doLast {
        destinationDir.mkdirs()
    }
}

val hive3HiveLibConflictPatterns = listOf(
    "guava-*.jar",
    "failureaccess-*.jar",
    "listenablefuture-*.jar",
    "disruptor-*.jar",
)

val hive3GcsHiveLibJarPatterns = hive3HiveLibConflictPatterns

data class JarInstallPlan(
    val sourceDir: String,
    val targetDir: String,
    val jarPatterns: List<String> = listOf("*.jar"),
    val removeTargetPatterns: List<String> = emptyList(),
)

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
        append(image.hiveVersion)
        append("-hadoop-")
        append(hadoopVersion)
        if (custom) {
            append("-gcs-")
            append(gcsConnectorVersion)
        }
        append("-jdk")
        append(image.jdkVersion)
        if (custom) {
            append("-")
            append(project.version)
        }
    }

fun dockerImage(image: HiveTarballImage, custom: Boolean): String {
    val repository = if (custom) image.customRepository else image.vanillaRepository
    return "${registry.get()}/$repository:${dockerTagSuffix(image, custom)}"
}

fun dockerImageTags(image: HiveTarballImage, custom: Boolean): List<String> {
    val versionTag = dockerImage(image, custom)
    return listOf(versionTag, "$versionTag-${gitCommitShort.get()}")
}

fun String.cleanDockerfile(): String =
    lineSequence()
        .map { it.trimStart() }
        .joinToString("\n")
        .trim()

fun renderJarInstallPlan(plan: JarInstallPlan): String {
    val copyGlobs = plan.jarPatterns.joinToString(" ") { pattern -> "${plan.sourceDir}/$pattern" }
    val removeBlock = if (plan.removeTargetPatterns.isEmpty()) {
        ""
    } else {
        val removeGlobs = plan.removeTargetPatterns.joinToString(" ") { pattern -> "${plan.targetDir}/$pattern" }
        """if [ "${'$'}JAR_CONFLICT_STRATEGY" = "remove" ]; then rm -f $removeGlobs; fi; \"""
    }

    return listOf(
        removeBlock,
        """
            for jar in $copyGlobs; do \
                [ -e "${'$'}jar" ] || continue; \
                cp "${'$'}jar" ${plan.targetDir}/; \
            done
        """.trimIndent(),
    ).filter(String::isNotBlank).joinToString("\n")
}

fun customJarInstallPlans(image: HiveTarballImage): List<JarInstallPlan> =
    buildList {
        add(
            JarInstallPlan(
                sourceDir = "/tmp/gcs-libs",
                targetDir = "/opt/hadoop/share/hadoop/common/lib",
            )
        )
        if (image.hiveVersion == "3.1.3") {
            add(
                JarInstallPlan(
                    sourceDir = "/tmp/gcs-libs",
                    targetDir = "/opt/hive/lib",
                    jarPatterns = hive3GcsHiveLibJarPatterns,
                    removeTargetPatterns = hive3HiveLibConflictPatterns,
                )
            )
        }
        add(
            JarInstallPlan(
                sourceDir = "/tmp/database-libs",
                targetDir = "/opt/hive/lib",
            )
        )
        if (priorityJarDir.isPresent) {
            add(
                JarInstallPlan(
                    sourceDir = "/tmp/priority-libs",
                    targetDir = "/opt/hive/lib",
                    removeTargetPatterns = priorityJarRemovePatterns.get(),
                )
            )
        }
    }

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
    val jarInstallCommands = customJarInstallPlans(image)
        .joinToString("; \\\n            ") { renderJarInstallPlan(it) }
    val priorityJarCopy = if (priorityJarDir.isPresent) {
        "COPY _shared/priority-libs/ /tmp/priority-libs/"
    } else {
        ""
    }
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

        ARG JAR_CONFLICT_STRATEGY=${jarConflictStrategy.get()}
        USER root
        COPY --chown=hive:hive $packagingDir/entrypoint.sh /entrypoint.sh
        COPY --chown=hive:hive $packagingDir/conf ${'$'}HIVE_HOME/conf
        COPY _shared/gcs-libs/ /tmp/gcs-libs/
        COPY _shared/database-libs/ /tmp/database-libs/
        $priorityJarCopy
        RUN set -eux; \
            chmod +x /entrypoint.sh; \
            mkdir -p /opt/hadoop/share/hadoop/common/lib; \
            $jarInstallCommands; \
            rm -rf /tmp/gcs-libs; \
            rm -rf /tmp/database-libs; \
            rm -rf /tmp/priority-libs; \
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
    inputs.property("mysqlConnectorVersion", mysqlConnectorVersion)
    inputs.property("jarConflictStrategy", jarConflictStrategy)
    inputs.property("priorityJarDir", priorityJarDir.orElse(""))
    inputs.property("priorityJarRemovePatterns", priorityJarRemovePatterns.map { it.joinToString(",") })

    doLast {
        require(jarConflictStrategy.get() in setOf("remove", "keep")) {
            "Unsupported image.jarConflictStrategy=${jarConflictStrategy.get()}. Use 'remove' or 'keep'."
        }
        if (priorityJarDir.isPresent) {
            val jarDir = file(priorityJarDir.get())
            require(jarDir.isDirectory) {
                "image.priorityJarDir must point to an existing directory: ${jarDir.absolutePath}"
            }
            require(jarDir.listFiles { file -> file.isFile && file.extension == "jar" }?.isNotEmpty() == true) {
                "image.priorityJarDir must contain at least one *.jar: ${jarDir.absolutePath}"
            }
        }
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
    imageTags: Provider<List<String>>,
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
        val tagArgs = imageTags.get().flatMap { listOf("-t", it) }
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
                *tagArgs.toTypedArray(),
                ".",
            )
        } else {
            commandLine(
                "docker",
                "build",
                "-f",
                dockerfile,
                *tagArgs.toTypedArray(),
                ".",
            )
        }
    }
}

fun registerDockerPushTask(
    name: String,
    imageTags: Provider<List<String>>,
    buildTaskName: String,
) {
    tasks.register<Exec>(name) {
        dependsOn(buildTaskName)
        commandLine("sh", "-c", imageTags.get().joinToString(" && ") { "docker push $it" })
    }
}

images.forEach { image ->
    val vanillaBuildTask = "dockerBuildVanilla${image.taskSuffix}"
    val customBuildTask = "dockerBuildCustom${image.taskSuffix}"
    registerDockerBuildTask(
        name = vanillaBuildTask,
        dockerfileName = "${image.taskSuffix}/Dockerfile.vanilla",
        imageTags = provider { dockerImageTags(image, custom = false) },
        needsTarballs = true,
        cacheScope = "vanilla-${image.taskSuffix}",
    )
    registerDockerBuildTask(
        name = customBuildTask,
        dockerfileName = "${image.taskSuffix}/Dockerfile.custom",
        imageTags = provider { dockerImageTags(image, custom = true) },
        needsTarballs = false,
        cacheScope = "custom-${image.taskSuffix}",
        extraDependsOn = if (image.hiveVersion == "3.1.3") {
            listOf(stageGcsDependencies, stageDatabaseDependencies, stageHive3Dependencies, stagePriorityJarDependencies)
        } else {
            listOf(stageGcsDependencies, stageDatabaseDependencies, stagePriorityJarDependencies)
        },
    )
    tasks.named(customBuildTask) {
        mustRunAfter(vanillaBuildTask)
    }
    registerDockerPushTask(
        name = "dockerPushVanilla${image.taskSuffix}",
        imageTags = provider { dockerImageTags(image, custom = false) },
        buildTaskName = vanillaBuildTask,
    )
    registerDockerPushTask(
        name = "dockerPushCustom${image.taskSuffix}",
        imageTags = provider { dockerImageTags(image, custom = true) },
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
