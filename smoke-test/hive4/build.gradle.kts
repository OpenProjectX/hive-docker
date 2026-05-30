plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    testImplementation(libs.hiveMetastore)
    testImplementation(libs.junitJupiter)
    testImplementation(libs.slf4jSimple)
    testImplementation(libs.testcontainersJunitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

data class SmokeSubject(
    val id: String,
    val image: Provider<String>,
    val customBuildTask: String,
    val vanillaBuildTask: String,
    val environment: Map<String, String> = emptyMap(),
)

val imageRegistry = providers.gradleProperty("imageRegistry").orElse("ghcr.io/openprojectx")
val subjects = listOf(
    SmokeSubject(
        id = "hive-standalone-metastore-4",
        image = providers.gradleProperty("smoke.image.hive-standalone-metastore-4").orElse(
            provider {
                "${imageRegistry.get()}/hive-standalone-metastore:" +
                    "${project.version}-${libs.versions.hive.get()}-hadoop-${libs.versions.hadoop.get()}" +
                    "-gcs-${libs.versions.gcsConnector.get()}-jdk21"
            }
        ),
        customBuildTask = ":image:dockerBuildCustomStandaloneMetastore420",
        vanillaBuildTask = ":image:dockerBuildVanillaStandaloneMetastore420",
    ),
    SmokeSubject(
        id = "hive4",
        image = providers.gradleProperty("smoke.image.hive4").orElse(
            provider {
                "${imageRegistry.get()}/hive:" +
                    "${project.version}-${libs.versions.hive.get()}-hadoop-${libs.versions.hadoop.get()}" +
                    "-gcs-${libs.versions.gcsConnector.get()}-jdk21"
            }
        ),
        customBuildTask = ":image:dockerBuildCustomHive420",
        vanillaBuildTask = ":image:dockerBuildVanillaHive420",
        environment = mapOf("SERVICE_NAME" to "metastore"),
    ),
)
val enabledSubjects = providers.gradleProperty("smoke.subjects")
    .orElse("hive3,hive4,hive-standalone-metastore-4")
    .map { value ->
        value.split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
val selectedSubjects = provider { subjects.filter { it.id in enabledSubjects.get() } }
val buildImage = providers.gradleProperty("smoke.buildImage").map(String::toBoolean).orElse(false)
val buildVanillaImage = providers.gradleProperty("smoke.buildVanillaImage").map(String::toBoolean).orElse(false)

tasks.test {
    useJUnitPlatform()
    onlyIf { selectedSubjects.get().isNotEmpty() }

    if (buildImage.get()) {
        dependsOn(selectedSubjects.get().map { it.customBuildTask })
    }
    if (buildVanillaImage.get()) {
        dependsOn(selectedSubjects.get().map { it.vanillaBuildTask })
    }

    environment("DOCKER_API_VERSION", System.getenv("DOCKER_API_VERSION") ?: "1.40")
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.40")
    systemProperty("smoke.hive4.subjects", selectedSubjects.get().joinToString(",") { it.id })
    selectedSubjects.get().forEach { subject ->
        systemProperty("smoke.hive4.${subject.id}.image", subject.image.get())
        if (subject.environment.isNotEmpty()) {
            systemProperty(
                "smoke.hive4.${subject.id}.environment",
                subject.environment.entries.joinToString(",") { "${it.key}=${it.value}" },
            )
        }
    }
    doFirst {
        selectedSubjects.get().forEach { subject ->
            logger.lifecycle("Running {} smoke test with Hive 4 client against {}", subject.id, subject.image.get())
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
