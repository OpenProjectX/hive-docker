package org.openprojectx.hive.docker.smoke.hive3;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Hive3MetastoreSmokeTest {
    private static final int METASTORE_PORT = 9083;

    @Test
    void hive3ImageAcceptsHive3MetastoreClientRequests() throws Exception {
        try (GenericContainer<?> metastore = metastoreContainer()) {
            metastore.start();

            HiveConf conf = new HiveConf();
            conf.setVar(HiveConf.ConfVars.METASTOREURIS, thriftUri(metastore));

            try (HiveMetaStoreClient client = new HiveMetaStoreClient(conf)) {
                Database database = new Database();
                database.setName("smoke_hive3");
                database.setDescription("Created by hive-docker Hive 3 smoke test");
                database.setLocationUri("file:/tmp/hive-smoke/smoke_hive3.db");

                createDatabaseIfMissing(client, database);

                List<String> databases = client.getDatabases("smoke_*");
                assertTrue(databases.contains("smoke_hive3"), "Hive 3 HMS should return smoke_hive3");
            }
        }
    }

    private GenericContainer<?> metastoreContainer() {
        return new GenericContainer<>(DockerImageName.parse(System.getProperty("smoke.hive3.image")))
            .withExposedPorts(METASTORE_PORT)
            .withEnv(Map.of("SERVICE_NAME", "metastore"))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));
    }

    private String thriftUri(GenericContainer<?> metastore) {
        return "thrift://" + metastore.getHost() + ":" + metastore.getMappedPort(METASTORE_PORT);
    }

    private void createDatabaseIfMissing(HiveMetaStoreClient client, Database database) throws TException {
        if (!client.getDatabases(database.getName()).contains(database.getName())) {
            client.createDatabase(database);
        }
    }
}
