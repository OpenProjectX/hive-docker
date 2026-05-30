plugins {
    base
}

val knownSmokeSubjects = setOf("hive3", "hive4", "hive-standalone-metastore-4")
val enabledSmokeSubjects = providers.gradleProperty("smoke.subjects")
    .orElse(knownSmokeSubjects.joinToString(","))
    .map { value ->
        value.split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

tasks.register("test") {
    doFirst {
        val unknownSubjects = enabledSmokeSubjects.get().filter { it !in knownSmokeSubjects }
        check(unknownSubjects.isEmpty()) {
            "Unknown smoke subjects: ${unknownSubjects.joinToString(", ")}"
        }
    }
    dependsOn(":smoke-test:hive3:test", ":smoke-test:hive4:test")
}

tasks.register("testClasses") {
    dependsOn(":smoke-test:hive3:testClasses", ":smoke-test:hive4:testClasses")
}
