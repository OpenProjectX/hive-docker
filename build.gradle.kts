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
    val releaseKind = providers.gradleProperty("release.kind").orElse("all").get()
    buildTasks.set(
        when (releaseKind) {
            "all" -> listOf(
                ":image:dockerReleaseImages",
                ":testcontainers:test",
                ":testcontainers:publishToSonatype",
                "closeAndReleaseSonatypeStagingRepository",
            )
            "images" -> listOf(":image:dockerReleaseImages")
            "jar" -> listOf(
                ":testcontainers:test",
                ":testcontainers:publishToSonatype",
                "closeAndReleaseSonatypeStagingRepository",
            )
            else -> throw GradleException("Unsupported release.kind=$releaseKind. Use 'all', 'images', or 'jar'.")
        }
    )
    versionPropertyFile.set("gradle.properties")
    tagTemplate.set("\$name-\$version")

    with(git) {
        requireBranch.set("master")
    }
}

gradle.projectsEvaluated {
    project(":testcontainers").tasks.named("test") {
        mustRunAfter(":image:dockerReleaseImages")
    }
    project(":testcontainers").tasks.named("publishToSonatype") {
        mustRunAfter(":image:dockerReleaseImages")
        mustRunAfter(":testcontainers:test")
    }
    tasks.named("closeAndReleaseSonatypeStagingRepository") {
        mustRunAfter(":testcontainers:publishToSonatype")
    }
}
