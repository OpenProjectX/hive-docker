package org.openprojectx.hive.docker.testcontainers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HiveDockerImagesTest {
    @Test
    void placesProjectVersionAtEndOfCustomImageTag() {
        assertEquals(
            "ghcr.io/openprojectx/hive:4.2.0-hadoop-3.4.2-gcs-4.0.4-jdk21-0.1.2-SNAPSHOT",
            HiveDockerImages.hive4("0.1.2-SNAPSHOT").asCanonicalNameString()
        );
    }
}
