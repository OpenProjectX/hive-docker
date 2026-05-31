import net.researchgate.release.ReleaseExtension

plugins {
    id("net.researchgate.release") version "3.1.0"

}

allprojects {
    group = "org.openprojectx.hive.docker.core"
}


subprojects {
    tasks.register<DependencyReportTask>("allDependencies") {}
}

tasks.register("smokeTest") {
    dependsOn(":smoke-test:test")
}

configure<ReleaseExtension> {
    buildTasks.set(listOf(":image:dockerReleaseImages", ":testcontainers:test", ":testcontainers:publish"))
    versionPropertyFile.set("gradle.properties")
    tagTemplate.set("\$name-\$version")

    with(git) {
        requireBranch.set("master")
    }
}
