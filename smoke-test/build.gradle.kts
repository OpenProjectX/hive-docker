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

val imageRegistry = providers.gradleProperty("imageRegistry").orElse("ghcr.io/openprojectx")
val smokeImage = providers.gradleProperty("smoke.hms.image")
    .orElse(
        provider {
            "${imageRegistry.get()}/hive-standalone-metastore:" +
                "${project.version}-${libs.versions.hive.get()}-hadoop-${libs.versions.hadoop.get()}" +
                "-gcs-${libs.versions.gcsConnector.get()}-jdk21"
        }
    )
val buildImage = providers.gradleProperty("smoke.buildImage")
    .map(String::toBoolean)
    .orElse(false)

tasks.test {
    useJUnitPlatform()

    if (buildImage.get()) {
        dependsOn(
            ":image:dockerBuildVanillaStandaloneMetastore420",
            ":image:dockerBuildCustomStandaloneMetastore420",
        )
    }

    environment("DOCKER_API_VERSION", System.getenv("DOCKER_API_VERSION") ?: "1.40")
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.40")
    systemProperty("smoke.hms.image", smokeImage.get())
    doFirst {
        logger.lifecycle("Running HMS smoke test against {}", smokeImage.get())
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
