package org.openprojectx.hive.docker.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Preconfigured Testcontainers container for the hive-docker metastore service.
 */
public class HiveMetastoreContainer extends GenericContainer<HiveMetastoreContainer> {
    public static final int METASTORE_PORT = 9083;

    public HiveMetastoreContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        withExposedPorts(METASTORE_PORT);
        waitingFor(Wait.forLogMessage(".*Started the new metaserver on port.*\\n", 1)
            .withStartupTimeout(Duration.ofMinutes(4)));
    }

    public static HiveMetastoreContainer hive3(String projectVersion) {
        return fullHiveImage(HiveDockerImages.hive3(projectVersion));
    }

    public static HiveMetastoreContainer hive3(String registry, String projectVersion) {
        return fullHiveImage(HiveDockerImages.hive3(registry, projectVersion));
    }

    public static HiveMetastoreContainer hive4(String projectVersion) {
        return fullHiveImage(HiveDockerImages.hive4(projectVersion));
    }

    public static HiveMetastoreContainer hive4(String registry, String projectVersion) {
        return fullHiveImage(HiveDockerImages.hive4(registry, projectVersion));
    }

    public static HiveMetastoreContainer standaloneMetastore4(String projectVersion) {
        return new HiveMetastoreContainer(HiveDockerImages.standaloneMetastore4(projectVersion));
    }

    public static HiveMetastoreContainer standaloneMetastore4(String registry, String projectVersion) {
        return new HiveMetastoreContainer(HiveDockerImages.standaloneMetastore4(registry, projectVersion));
    }

    public HiveMetastoreContainer withPostgres(
        String host,
        int port,
        String database,
        String username,
        String password
    ) {
        withEnv("DB_DRIVER", "postgres");
        withEnv("POSTGRES_HOST", host);
        withEnv("POSTGRES_PORT", Integer.toString(port));
        withEnv("POSTGRES_DB", database);
        withEnv("POSTGRES_USER", username);
        withEnv("POSTGRES_PASSWORD", password);
        return self();
    }

    public HiveMetastoreContainer withMysql(
        String host,
        int port,
        String database,
        String username,
        String password
    ) {
        withEnv("DB_DRIVER", "mysql");
        withEnv("MYSQL_HOST", host);
        withEnv("MYSQL_PORT", Integer.toString(port));
        withEnv("MYSQL_DB", database);
        withEnv("MYSQL_USER", username);
        withEnv("MYSQL_PASSWORD", password);
        return self();
    }

    public HiveMetastoreContainer withResume(boolean resume) {
        withEnv("IS_RESUME", Boolean.toString(resume));
        return self();
    }

    public HiveMetastoreContainer withWarehousePath(String warehousePath) {
        withEnv("HIVE_WAREHOUSE_PATH", warehousePath);
        return self();
    }

    public String getThriftUri() {
        return "thrift://" + getHost() + ":" + getMappedPort(METASTORE_PORT);
    }

    private static HiveMetastoreContainer fullHiveImage(DockerImageName dockerImageName) {
        return new HiveMetastoreContainer(dockerImageName)
            .withEnv("SERVICE_NAME", "metastore");
    }
}
