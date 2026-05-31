import net.researchgate.release.ReleaseExtension

plugins {
    id("net.researchgate.release") version "3.1.0"
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"

}

allprojects {
    group = "org.openprojectx.hive.docker.core"
}


subprojects {
    tasks.register<DependencyReportTask>("allDependencies") {}
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("OSSRH_USERNAME"))
            password.set(System.getenv("OSSRH_PASSWORD"))
        }
    }
}

tasks.register("smokeTest") {
    dependsOn(":smoke-test:test")
}

configure<ReleaseExtension> {
    val releaseKind = providers.gradleProperty("release.kind").orElse("images").get()
    buildTasks.set(
        when (releaseKind) {
            "images" -> listOf(":image:dockerReleaseImages")
            "jar" -> listOf(
                ":testcontainers:test",
                ":testcontainers:publishToSonatype",
                "closeAndReleaseSonatypeStagingRepository",
            )
            else -> throw GradleException("Unsupported release.kind=$releaseKind. Use 'images' or 'jar'.")
        }
    )
    versionPropertyFile.set("gradle.properties")
    tagTemplate.set("\$name-\$version")

    with(git) {
        requireBranch.set("master")
    }
}
