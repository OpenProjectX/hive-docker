package org.openprojectx.hive.docker.smoke.hive4;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.thrift.TException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Hive4MetastoreSmokeTest {
    private static final int METASTORE_PORT = 9083;
    private static final String POSTGRES_ALIAS = "postgres";
    private static final String POSTGRES_DATABASE = "metastore";
    private static final String POSTGRES_USER = "hive";
    private static final String POSTGRES_PASSWORD = "hive";
    private static final String MYSQL_ALIAS = "mysql";
    private static final String MYSQL_DATABASE = "metastore";
    private static final String MYSQL_USER = "hive";
    private static final String MYSQL_PASSWORD = "hive";

    static Stream<Subject> hive4Subjects() {
        String subjects = System.getProperty("smoke.hive4.subjects", "");
        return Arrays.stream(subjects.split(","))
            .map(String::trim)
            .filter(subject -> !subject.isEmpty())
            .map(subject -> new Subject(
                subject,
                System.getProperty("smoke.hive4." + subject + ".image"),
                environment(subject)
            ));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hive4Subjects")
    void hive4ImageAcceptsHive4MetastoreClientRequests(Subject subject) throws Exception {
        try (GenericContainer<?> metastore = metastoreContainer(subject)) {
            metastore.start();

            assertMetastoreAcceptsClientRequests(subject.id(), metastore, "smoke_" + subject.id().replace('-', '_'));
        }
    }

    @ParameterizedTest(name = "{0} with PostgreSQL")
    @MethodSource("hive4Subjects")
    void hive4ImageInitializesPostgresSchemaAndAcceptsHiveMetastoreClientRequests(Subject subject) throws Exception {
        try (
            Network network = Network.newNetwork();
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName(POSTGRES_DATABASE)
                .withUsername(POSTGRES_USER)
                .withPassword(POSTGRES_PASSWORD)
                .withNetwork(network)
                .withNetworkAliases(POSTGRES_ALIAS);
            GenericContainer<?> metastore = postgresMetastoreContainer(subject, network)
        ) {
            postgres.start();
            metastore.start();

            assertMetastoreAcceptsClientRequests(
                subject.id(),
                metastore,
                "smoke_pg_" + subject.id().replace('-', '_')
            );
        }
    }

    @ParameterizedTest(name = "{0} with MySQL")
    @MethodSource("hive4Subjects")
    void hive4ImageInitializesMysqlSchemaAndAcceptsHiveMetastoreClientRequests(Subject subject) throws Exception {
        try (
            Network network = Network.newNetwork();
            MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.44-bookworm")
                .withDatabaseName(MYSQL_DATABASE)
                .withUsername(MYSQL_USER)
                .withPassword(MYSQL_PASSWORD)
                .withNetwork(network)
                .withNetworkAliases(MYSQL_ALIAS);
            GenericContainer<?> metastore = mysqlMetastoreContainer(subject, network)
        ) {
            mysql.start();
            metastore.start();

            assertMetastoreAcceptsClientRequests(
                subject.id(),
                metastore,
                "smoke_mysql_" + subject.id().replace('-', '_')
            );
        }
    }

    private void assertMetastoreAcceptsClientRequests(
        String subjectId,
        GenericContainer<?> metastore,
        String databaseName
    ) throws Exception {
        HiveConf conf = new HiveConf();
        conf.setVar(HiveConf.ConfVars.METASTORE_URIS, thriftUri(metastore));
        conf.setBoolVar(HiveConf.ConfVars.METASTORE_CLIENT_CACHE_ENABLED, false);

        try (HiveMetaStoreClient client = new HiveMetaStoreClient(conf)) {
            Database database = new Database();
            database.setName(databaseName);
            database.setDescription("Created by hive-docker Hive 4 smoke test for " + subjectId);
            database.setLocationUri("file:/tmp/hive-smoke/" + database.getName() + ".db");

            createDatabaseIfMissing(client, database);

            List<String> databases = client.getDatabases("smoke_*");
            assertTrue(databases.contains(database.getName()), subjectId + " should return " + database.getName());
        }
    }

    private GenericContainer<?> metastoreContainer(Subject subject) {
        return withOptionalContainerLogs(new GenericContainer<>(DockerImageName.parse(subject.image()))
            .withExposedPorts(METASTORE_PORT)
            .withEnv(subject.environment())
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3))));
    }

    private GenericContainer<?> postgresMetastoreContainer(Subject subject, Network network) {
        Map<String, String> environment = new LinkedHashMap<>(subject.environment());
        environment.put("DB_DRIVER", "postgres");
        environment.put("POSTGRES_HOST", POSTGRES_ALIAS);
        environment.put("POSTGRES_PORT", "5432");
        environment.put("POSTGRES_DB", POSTGRES_DATABASE);
        environment.put("POSTGRES_USER", POSTGRES_USER);
        environment.put("POSTGRES_PASSWORD", POSTGRES_PASSWORD);

        return withOptionalContainerLogs(new GenericContainer<>(DockerImageName.parse(subject.image()))
            .withNetwork(network)
            .withExposedPorts(METASTORE_PORT)
            .withEnv(environment)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4))));
    }

    private GenericContainer<?> mysqlMetastoreContainer(Subject subject, Network network) {
        Map<String, String> environment = new LinkedHashMap<>(subject.environment());
        environment.put("DB_DRIVER", "mysql");
        environment.put("MYSQL_HOST", MYSQL_ALIAS);
        environment.put("MYSQL_PORT", "3306");
        environment.put("MYSQL_DB", MYSQL_DATABASE);
        environment.put("MYSQL_USER", MYSQL_USER);
        environment.put("MYSQL_PASSWORD", MYSQL_PASSWORD);

        return withOptionalContainerLogs(new GenericContainer<>(DockerImageName.parse(subject.image()))
            .withNetwork(network)
            .withExposedPorts(METASTORE_PORT)
            .withEnv(environment)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4))));
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

    private static Map<String, String> environment(String subject) {
        String raw = System.getProperty("smoke.hive4." + subject + ".environment", "");
        Map<String, String> environment = new LinkedHashMap<>();
        if (raw.isBlank()) {
            return environment;
        }
        for (String entry : raw.split(",")) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                environment.put(parts[0], parts[1]);
            }
        }
        return environment;
    }

    record Subject(String id, String image, Map<String, String> environment) {
        @Override
        public String toString() {
            return id;
        }
    }
}
