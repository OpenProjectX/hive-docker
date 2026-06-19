package org.openprojectx.hive.docker.smoke.hive3;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Hive3MetastoreSmokeTest {
    private static final int METASTORE_PORT = 9083;
    private static final String POSTGRES_ALIAS = "postgres";
    private static final String POSTGRES_DATABASE = "metastore";
    private static final String POSTGRES_USER = "hive";
    private static final String POSTGRES_PASSWORD = "hive";
    private static final String MYSQL_ALIAS = "mysql";
    private static final String MYSQL_DATABASE = "metastore";
    private static final String MYSQL_USER = "hive";
    private static final String MYSQL_PASSWORD = "hive";

    @Test
    void hive3ImageAcceptsHive3MetastoreClientRequests() throws Exception {
        try (GenericContainer<?> metastore = metastoreContainer()) {
            metastore.start();

            assertMetastoreAcceptsClientRequests(metastore, "smoke_hive3");
        }
    }

    @Test
    void hive3ImageInitializesPostgresSchemaAndAcceptsHive3MetastoreClientRequests() throws Exception {
        try (
            Network network = Network.newNetwork();
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName(POSTGRES_DATABASE)
                .withUsername(POSTGRES_USER)
                .withPassword(POSTGRES_PASSWORD)
                .withNetwork(network)
                .withNetworkAliases(POSTGRES_ALIAS);
            GenericContainer<?> metastore = postgresMetastoreContainer(network)
        ) {
            postgres.start();
            metastore.start();

            assertMetastoreAcceptsClientRequests(metastore, "smoke_pg_hive3");
        }
    }

    @Test
    void hive3ImageInitializesMysqlSchemaAndAcceptsHive3MetastoreClientRequests() throws Exception {
        try (
            Network network = Network.newNetwork();
            MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.44-bookworm")
                .withDatabaseName(MYSQL_DATABASE)
                .withUsername(MYSQL_USER)
                .withPassword(MYSQL_PASSWORD)
                .withNetwork(network)
                .withNetworkAliases(MYSQL_ALIAS);
            GenericContainer<?> metastore = mysqlMetastoreContainer(network)
        ) {
            mysql.start();
            metastore.start();

            assertMetastoreAcceptsClientRequests(metastore, "smoke_mysql_hive3");
        }
    }

    private GenericContainer<?> metastoreContainer() {
        return withOptionalContainerLogs(new GenericContainer<>(DockerImageName.parse(System.getProperty("smoke.hive3.image")))
            .withExposedPorts(METASTORE_PORT)
            .withEnv(Map.of("SERVICE_NAME", "metastore"))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3))));
    }

    private GenericContainer<?> postgresMetastoreContainer(Network network) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("SERVICE_NAME", "metastore");
        environment.put("DB_DRIVER", "postgres");
        environment.put("POSTGRES_HOST", POSTGRES_ALIAS);
        environment.put("POSTGRES_PORT", "5432");
        environment.put("POSTGRES_DB", POSTGRES_DATABASE);
        environment.put("POSTGRES_USER", POSTGRES_USER);
        environment.put("POSTGRES_PASSWORD", POSTGRES_PASSWORD);

        return withOptionalContainerLogs(new GenericContainer<>(DockerImageName.parse(System.getProperty("smoke.hive3.image")))
            .withNetwork(network)
            .withExposedPorts(METASTORE_PORT)
            .withEnv(environment)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4))));
    }

    private GenericContainer<?> mysqlMetastoreContainer(Network network) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("SERVICE_NAME", "metastore");
        environment.put("DB_DRIVER", "mysql");
        environment.put("MYSQL_HOST", MYSQL_ALIAS);
        environment.put("MYSQL_PORT", "3306");
        environment.put("MYSQL_DB", MYSQL_DATABASE);
        environment.put("MYSQL_USER", MYSQL_USER);
        environment.put("MYSQL_PASSWORD", MYSQL_PASSWORD);

        return withOptionalContainerLogs(new GenericContainer<>(DockerImageName.parse(System.getProperty("smoke.hive3.image")))
            .withNetwork(network)
            .withExposedPorts(METASTORE_PORT)
            .withEnv(environment)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4))));
    }

    private void assertMetastoreAcceptsClientRequests(
        GenericContainer<?> metastore,
        String databaseName
    ) throws Exception {
        HiveConf conf = new HiveConf();
        conf.setVar(HiveConf.ConfVars.METASTOREURIS, thriftUri(metastore));

        try (HiveMetaStoreClient client = new HiveMetaStoreClient(conf)) {
            Database database = new Database();
            database.setName(databaseName);
            database.setDescription("Created by hive-docker Hive 3 smoke test");
            database.setLocationUri("file:/tmp/hive-smoke/" + databaseName + ".db");

            createDatabaseIfMissing(client, database);

            List<String> databases = client.getDatabases("smoke_*");
            assertTrue(databases.contains(databaseName), "Hive 3 HMS should return " + databaseName);
        }
    }

    private String thriftUri(GenericContainer<?> metastore) {
        return "thrift://" + metastore.getHost() + ":" + metastore.getMappedPort(METASTORE_PORT);
    }

    private GenericContainer<?> withOptionalContainerLogs(GenericContainer<?> container) {
        if (Boolean.getBoolean("smoke.containerLogs")) {
            container.withLogConsumer(frame -> System.err.print(frame.getUtf8String()));
        }
        return container;
    }

    private void createDatabaseIfMissing(HiveMetaStoreClient client, Database database) throws TException {
        if (!client.getDatabases(database.getName()).contains(database.getName())) {
            client.createDatabase(database);
        }
    }
}
