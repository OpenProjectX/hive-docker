plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    testImplementation(libs.hiveMetastore3)
    testImplementation(libs.hadoopMapreduceClientCore)
    testImplementation(libs.junitJupiter)
    testImplementation(libs.slf4jSimple)
    testImplementation(libs.testcontainersJunitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

val subjectId = "hive3"
val imageRegistry = providers.gradleProperty("imageRegistry").orElse("ghcr.io/openprojectx")
val image = providers.gradleProperty("smoke.image.$subjectId").orElse(
    provider {
        "${imageRegistry.get()}/hive:" +
            "${project.version}-3.1.3-hadoop-${libs.versions.hadoop.get()}" +
            "-gcs-${libs.versions.gcsConnector.get()}-jdk17"
    }
)
val enabledSubjects = providers.gradleProperty("smoke.subjects")
    .orElse("hive3,hive4,hive-standalone-metastore-4")
    .map { value ->
        value.split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
val subjectEnabled = provider { subjectId in enabledSubjects.get() }
val buildImage = providers.gradleProperty("smoke.buildImage").map(String::toBoolean).orElse(false)
val buildVanillaImage = providers.gradleProperty("smoke.buildVanillaImage").map(String::toBoolean).orElse(false)

tasks.test {
    useJUnitPlatform()
    onlyIf { subjectEnabled.get() }

    if (subjectEnabled.get() && buildImage.get()) {
        dependsOn(":image:dockerBuildCustomHive313")
    }
    if (subjectEnabled.get() && buildVanillaImage.get()) {
        dependsOn(":image:dockerBuildVanillaHive313")
    }

    environment("DOCKER_API_VERSION", System.getenv("DOCKER_API_VERSION") ?: "1.40")
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.40")
    systemProperty("smoke.hive3.image", image.get())
    doFirst {
        logger.lifecycle("Running {} smoke test with Hive 3 client against {}", subjectId, image.get())
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
