plugins {
    `java-library`
    `maven-publish`
}

base {
    archivesName.set("hive-docker-testcontainers")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withJavadocJar()
    withSourcesJar()
}

val generatedImageVersions = layout.buildDirectory.dir("generated/sources/imageVersions/java/main")
val generateImageVersionSources by tasks.registering {
    val outputDir = generatedImageVersions
    inputs.property("hadoopVersion", libs.versions.hadoop)
    inputs.property("gcsConnectorVersion", libs.versions.gcsConnector)
    inputs.property("hive3Version", libs.versions.hive3)
    inputs.property("hive4Version", libs.versions.hive)
    outputs.dir(outputDir)

    doLast {
        val packageDir = outputDir.get().asFile.resolve("org/openprojectx/hive/docker/testcontainers")
        packageDir.mkdirs()
        packageDir.resolve("HiveDockerImageVersions.java").writeText(
            """
            package org.openprojectx.hive.docker.testcontainers;

            final class HiveDockerImageVersions {
                static final String HADOOP_VERSION = "${libs.versions.hadoop.get()}";
                static final String GCS_CONNECTOR_VERSION = "${libs.versions.gcsConnector.get()}";
                static final String HIVE3_VERSION = "${libs.versions.hive3.get()}";
                static final String HIVE4_VERSION = "${libs.versions.hive.get()}";

                private HiveDockerImageVersions() {
                }
            }
            """.trimIndent()
        )
    }
}

sourceSets {
    main {
        java.srcDir(generatedImageVersions)
    }
}

dependencies {
    api(libs.testcontainers)
    testImplementation(libs.bundles.gcsRuntime)
    testImplementation(libs.hadoopAws)
    testImplementation(libs.hadoopClientApi)
    testImplementation(libs.hadoopClientRuntime)
    testImplementation(libs.icebergSparkRuntime)
    testImplementation(libs.junitJupiter)
    testImplementation(libs.sparkHive)
    testImplementation(libs.sparkSql)
    testImplementation(libs.testcontainersLocalstack)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.compileJava {
    dependsOn(generateImageVersionSources)
}

tasks.named("sourcesJar") {
    dependsOn(generateImageVersionSources)
}

tasks.javadoc {
    dependsOn(generateImageVersionSources)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Testcontainers tests must always execute") { true }
    if (
        !providers.gradleProperty("testcontainers.skipStorageSmoke").map(String::toBoolean).orElse(false).get()
        && !providers.gradleProperty("testcontainers.hmsImage").isPresent
    ) {
        dependsOn(":image:dockerBuildCustomHive313")
    }
    environment("DOCKER_API_VERSION", System.getenv("DOCKER_API_VERSION") ?: "1.40")
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.40")
    systemProperty("hiveDocker.test.imageRegistry", providers.gradleProperty("imageRegistry").orElse("openprojectx").get())
    systemProperty("hiveDocker.test.projectVersion", project.version.toString())
    providers.gradleProperty("testcontainers.hmsImage").orNull?.let {
        systemProperty("hiveDocker.test.hmsImage", it)
    }
    if (providers.gradleProperty("testcontainers.skipStorageSmoke").map(String::toBoolean).orElse(false).get()) {
        useJUnitPlatform {
            excludeTags("storage-smoke")
        }
    }
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
    systemProperty("spark.ui.enabled", "false")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "hive-docker-testcontainers"

            pom {
                name.set("hive-docker-testcontainers")
                description.set("Testcontainers helpers for OpenProjectX Hive Docker images")
                url.set("https://github.com/openprojectx/hive-docker")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("openprojectx")
                        name.set("OpenProjectX")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/openprojectx/hive-docker.git")
                    developerConnection.set("scm:git:https://github.com/openprojectx/hive-docker.git")
                    url.set("https://github.com/openprojectx/hive-docker")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/openprojectx/hive-docker")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
    }
}
