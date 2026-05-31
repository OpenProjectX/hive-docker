package org.openprojectx.hive.docker.testcontainers;

import org.testcontainers.utility.DockerImageName;

/**
 * Image name helpers for the custom hive-docker images.
 */
public final class HiveDockerImages {
    public static final String DEFAULT_REGISTRY = "ghcr.io/openprojectx";
    public static final String HADOOP_VERSION = HiveDockerImageVersions.HADOOP_VERSION;
    public static final String GCS_CONNECTOR_VERSION = HiveDockerImageVersions.GCS_CONNECTOR_VERSION;
    public static final String HIVE3_VERSION = HiveDockerImageVersions.HIVE3_VERSION;
    public static final String HIVE4_VERSION = HiveDockerImageVersions.HIVE4_VERSION;

    private HiveDockerImages() {
    }

    public static DockerImageName hive3(String projectVersion) {
        return hive3(DEFAULT_REGISTRY, projectVersion);
    }

    public static DockerImageName hive3(String registry, String projectVersion) {
        return DockerImageName.parse(registry + "/hive:" + customTag(HIVE3_VERSION, 17, projectVersion));
    }

    public static DockerImageName hive4(String projectVersion) {
        return hive4(DEFAULT_REGISTRY, projectVersion);
    }

    public static DockerImageName hive4(String registry, String projectVersion) {
        return DockerImageName.parse(registry + "/hive:" + customTag(HIVE4_VERSION, 21, projectVersion));
    }

    public static DockerImageName standaloneMetastore4(String projectVersion) {
        return standaloneMetastore4(DEFAULT_REGISTRY, projectVersion);
    }

    public static DockerImageName standaloneMetastore4(String registry, String projectVersion) {
        return DockerImageName.parse(
            registry + "/hive-standalone-metastore:" + customTag(HIVE4_VERSION, 21, projectVersion)
        );
    }

    public static String customTag(String hiveVersion, int jdkVersion, String projectVersion) {
        return hiveVersion
            + "-hadoop-" + HADOOP_VERSION
            + "-gcs-" + GCS_CONNECTOR_VERSION
            + "-jdk" + jdkVersion
            + "-" + projectVersion;
    }
}
