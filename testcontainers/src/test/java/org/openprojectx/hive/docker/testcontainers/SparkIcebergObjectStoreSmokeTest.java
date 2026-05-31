package org.openprojectx.hive.docker.testcontainers;

import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("storage-smoke")
class SparkIcebergObjectStoreSmokeTest {
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:4.14.0";
    private static final String FAKE_GCS_IMAGE = "fsouza/fake-gcs-server:1.54";
    private static final String S3_BUCKET_PREFIX = "iceberg-s3-smoke";
    private static final String GCS_BUCKET_PREFIX = "iceberg-gcs-smoke";

    @Test
    void sparkIcebergWritesAndReadsDatasetOnLocalStackS3() throws Exception {
        try (
            Network network = Network.newNetwork();
            LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("localstack")
                .withServices(LocalStackContainer.Service.S3)
        ) {
            localstack.start();
            String bucket = uniqueObjectStoreName(S3_BUCKET_PREFIX);
            createS3Bucket(localstack, bucket);
            String warehouse = "s3a://" + bucket + "/warehouse";

            try (HiveMetastoreContainer metastore = hmsContainer()
                .withNetwork(network)
                .withEnv("S3A_FILE_SYSTEM_IMPL", "org.apache.hadoop.fs.s3a.S3AFileSystem")
                .withEnv("S3_ENDPOINT_URL", "http://localstack:4566")
                .withEnv("AWS_ACCESS_KEY_ID", localstack.getAccessKey())
                .withEnv("AWS_SECRET_ACCESS_KEY", localstack.getSecretKey())
                .withEnv("S3_PATH_STYLE_ACCESS", "true")
                .withEnv("S3_SSL_ENABLED", "false")) {
                metastore.start();
                try (SparkSession spark = spark("iceberg-s3-smoke", Map.of(
                "spark.sql.catalog.smoke", "org.apache.iceberg.spark.SparkCatalog",
                "spark.sql.catalog.smoke.type", "hive",
                "spark.sql.catalog.smoke.uri", metastore.getThriftUri(),
                "spark.sql.catalog.smoke.warehouse", warehouse,
                "spark.hadoop.fs.s3a.endpoint", localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString(),
                "spark.hadoop.fs.s3a.access.key", localstack.getAccessKey(),
                "spark.hadoop.fs.s3a.secret.key", localstack.getSecretKey(),
                "spark.hadoop.fs.s3a.path.style.access", "true",
                "spark.hadoop.fs.s3a.connection.ssl.enabled", "false",
                "spark.hadoop.fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
                ))) {
                    assertIcebergRoundTrip(metastore, spark, uniqueTableName("s3_dataset"), warehouse + "/tables/" + uniqueId());
                }
            }
            assertS3ContainsParquet(localstack, bucket);
        }
    }

    @Test
    void sparkIcebergWritesAndReadsDatasetOnFakeGcs() throws Exception {
        try (
            Network network = Network.newNetwork();
            GenericContainer<?> gcs = new GenericContainer<>(DockerImageName.parse(FAKE_GCS_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("fake-gcs")
                .withExposedPorts(4443)
                .withCommand("-scheme", "http", "-port", "4443")
                .waitingFor(Wait.forHttp("/storage/v1/b").forPort(4443).withStartupTimeout(Duration.ofSeconds(30)))
        ) {
            gcs.start();
            String bucket = uniqueObjectStoreName(GCS_BUCKET_PREFIX);
            createGcsBucket(gcs, bucket);

            String rootUrl = "http://" + gcs.getHost() + ":" + gcs.getMappedPort(4443);
            String containerRootUrl = "http://fake-gcs:4443";
            String warehouse = "gs://" + bucket + "/warehouse";
            try (HiveMetastoreContainer metastore = hmsContainer()
                .withNetwork(network)
                .withEnv("GCS_FILE_SYSTEM_IMPL", "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem")
                .withEnv("GCS_ABSTRACT_FILE_SYSTEM_IMPL", "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFS")
                .withEnv("GCS_AUTH_TYPE", "UNAUTHENTICATED")
                .withEnv("GCS_PROJECT_ID", "hive-docker-smoke")
                .withEnv("GCS_STORAGE_ROOT_URL", containerRootUrl)
                .withEnv("GCS_STORAGE_SERVICE_PATH", "/storage/v1/")
                .withEnv("GCS_CREATE_ITEMS_CONFLICT_CHECK_ENABLED", "false")
                .withEnv("GCS_CLIENT_UPLOAD_TYPE", "WRITE_TO_DISK_THEN_UPLOAD")
                .withEnv("GCS_DIRECT_UPLOAD_ENABLED", "false")
                .withEnv("GCS_PERFORMANCE_CACHE_ENABLED", "false")
                .withEnv("GCS_STORAGE_CLIENT_CACHE_ENABLED", "false")
                .withEnv("GCS_GRPC_ENABLED", "false")) {
                metastore.start();
                try (SparkSession spark = spark("iceberg-gcs-smoke", Map.ofEntries(
                    entry("spark.sql.catalog.smoke", "org.apache.iceberg.spark.SparkCatalog"),
                    entry("spark.sql.catalog.smoke.type", "hive"),
                    entry("spark.sql.catalog.smoke.uri", metastore.getThriftUri()),
                    entry("spark.sql.catalog.smoke.warehouse", warehouse),
                    entry("spark.hadoop.fs.gs.impl", "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem"),
                    entry("spark.hadoop.fs.AbstractFileSystem.gs.impl", "com.google.cloud.hadoop.fs.gcs.GoogleHadoopFS"),
                    entry("spark.hadoop.fs.gs.auth.type", "UNAUTHENTICATED"),
                    entry("spark.hadoop.fs.gs.project.id", "hive-docker-smoke"),
                    entry("spark.hadoop.fs.gs.storage.root.url", rootUrl),
                    entry("spark.hadoop.fs.gs.storage.service.path", "/storage/v1/"),
                    entry("spark.hadoop.fs.gs.create.items.conflict.check.enable", "false"),
                    entry("spark.hadoop.fs.gs.client.upload.type", "WRITE_TO_DISK_THEN_UPLOAD"),
                    entry("spark.hadoop.fs.gs.outputstream.direct.upload.enable", "false"),
                    entry("spark.hadoop.fs.gs.performance.cache.enable", "false"),
                    entry("spark.hadoop.fs.gs.storage.client.cache.enable", "false"),
                    entry("spark.hadoop.fs.gs.grpc.enable", "false")
                ))) {
                    assertIcebergRoundTrip(metastore, spark, uniqueTableName("gcs_dataset"), warehouse + "/tables/" + uniqueId());
                }
            }
            assertGcsContainsParquet(gcs, bucket);
        }
    }

    private SparkSession spark(String appName, Map<String, String> config) {
        SparkSession.Builder builder = SparkSession.builder()
            .appName(appName)
            .master("local[2]")
            .config("spark.ui.enabled", "false")
            .config("spark.driver.bindAddress", "127.0.0.1")
            .config("spark.driver.host", "127.0.0.1")
            .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");

        config.forEach(builder::config);
        return builder.getOrCreate();
    }

    private HiveMetastoreContainer hmsContainer() {
        String hmsImage = System.getProperty("hiveDocker.test.hmsImage");
        HiveMetastoreContainer container = hmsImage == null || hmsImage.isBlank()
            ? HiveMetastoreContainer.hive3(
                System.getProperty("hiveDocker.test.imageRegistry", HiveDockerImages.DEFAULT_REGISTRY),
                System.getProperty("hiveDocker.test.projectVersion")
            )
            : new HiveMetastoreContainer(DockerImageName.parse(hmsImage)).withEnv("SERVICE_NAME", "metastore");
        return container.withWarehousePath("/tmp/hive-warehouse-" + uniqueId());
    }

    private void assertIcebergRoundTrip(
        HiveMetastoreContainer metastore,
        SparkSession spark,
        String tableName,
        String tableLocation
    ) {
        try {
            String table = "smoke.default." + tableName;
            spark.sql("CREATE NAMESPACE IF NOT EXISTS smoke.default");
            spark.sql("CREATE OR REPLACE TABLE " + table + " (id INT, name STRING) USING iceberg LOCATION '" + tableLocation + "'");
            spark.sql("INSERT INTO " + table + " VALUES (1, 'alpha'), (2, 'beta'), (3, 'gamma')");

            List<Row> rows = spark.sql("SELECT id, name FROM " + table + " ORDER BY id").collectAsList();

            assertEquals(3, rows.size());
            assertEquals(1, rows.get(0).getInt(0));
            assertEquals("alpha", rows.get(0).getString(1));
            assertEquals(2, rows.get(1).getInt(0));
            assertEquals("beta", rows.get(1).getString(1));
            assertEquals(3, rows.get(2).getInt(0));
            assertEquals("gamma", rows.get(2).getString(1));
        } catch (RuntimeException | AssertionError failure) {
            System.err.println("Hive metastore container logs:\n" + metastore.getLogs());
            throw failure;
        }
    }

    private void createS3Bucket(LocalStackContainer localstack, String bucket) throws IOException, InterruptedException {
        var result = localstack.execInContainer("awslocal", "s3", "mb", "s3://" + bucket);
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    private void createGcsBucket(GenericContainer<?> gcs, String bucket) throws IOException, InterruptedException {
        var result = gcs.execInContainer(
            "sh",
            "-c",
            "printf '{\"name\":\"" + bucket + "\"}' | wget -qO- --header='Content-Type: application/json' --post-file=- http://127.0.0.1:4443/storage/v1/b"
        );
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    private void assertS3ContainsParquet(LocalStackContainer localstack, String bucket) throws IOException, InterruptedException {
        var result = localstack.execInContainer("awslocal", "s3", "ls", "s3://" + bucket, "--recursive");
        assertEquals(0, result.getExitCode(), result.getStderr());
        assertTrue(result.getStdout().contains(".parquet"), result.getStdout());
    }

    private void assertGcsContainsParquet(GenericContainer<?> gcs, String bucket) throws IOException, InterruptedException {
        var result = gcs.execInContainer("wget", "-qO-", "http://127.0.0.1:4443/storage/v1/b/" + bucket + "/o");
        assertEquals(0, result.getExitCode(), result.getStderr());
        assertTrue(result.getStdout().contains(".parquet"), result.getStdout());
    }

    private String uniqueObjectStoreName(String prefix) {
        return prefix + "-" + uniqueId();
    }

    private String uniqueTableName(String prefix) {
        return prefix + "_" + uniqueId();
    }

    private String uniqueId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
