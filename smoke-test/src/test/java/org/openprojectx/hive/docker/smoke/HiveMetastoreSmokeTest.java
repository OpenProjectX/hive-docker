package org.openprojectx.hive.docker.smoke;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveMetastoreSmokeTest {
    private static final int METASTORE_PORT = 9083;

    @Test
    void customStandaloneMetastoreAcceptsHiveMetastoreClientRequests() throws Exception {
        String image = System.getProperty("smoke.hms.image");
        DockerImageName imageName = DockerImageName.parse(image);

        try (GenericContainer<?> metastore = new GenericContainer<>(imageName)
            .withExposedPorts(METASTORE_PORT)
            .waitingFor(
                Wait.forListeningPort()
                    .withStartupTimeout(Duration.ofMinutes(3))
            )) {
            metastore.start();

            String thriftUri = "thrift://" + metastore.getHost() + ":" + metastore.getMappedPort(METASTORE_PORT);
            HiveConf conf = new HiveConf();
            conf.setVar(HiveConf.ConfVars.METASTORE_URIS, thriftUri);
            conf.setBoolVar(HiveConf.ConfVars.METASTORE_CLIENT_CACHE_ENABLED, false);

            try (HiveMetaStoreClient client = new HiveMetaStoreClient(conf)) {
                Database database = new Database();
                database.setName("smoke_db");
                database.setDescription("Created by hive-docker smoke test");
                database.setLocationUri("file:/tmp/hive-smoke/smoke_db.db");

                createDatabaseIfMissing(client, database);

                List<String> databases = client.getDatabases("smoke_*");
                assertTrue(databases.contains("smoke_db"), "Metastore should return the smoke database");
            }
        }
    }

    private static void createDatabaseIfMissing(HiveMetaStoreClient client, Database database) throws TException {
        if (!client.getDatabases(database.getName()).contains(database.getName())) {
            client.createDatabase(database);
        }
    }
}
