package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Moves a real k3s-backed cluster one Kubernetes minor version up with UpdateClusterVersion, then its
 * node group with UpdateNodegroupVersion, and checks that the API server runs the new version on the
 * same data and port.
 */
@QuarkusTest
@TestProfile(EksClusterUpdateDockerIntegrationTest.Profile.class)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class EksClusterUpdateDockerIntegrationTest {

    /** A profile of its own so the CI partitioner can place this class apart from the other cluster classes. */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.eks.mock", "false");
        }
    }

    private static final String JSON = "application/json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    DockerClient dockerClient;

    @Inject
    EksService eksService;

    @BeforeEach
    void requireDocker() {
        boolean available;
        try {
            dockerClient.pingCmd().exec();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for EKS update tests");
    }

    /**
     * The control plane moves alone, keeping its data, and its managed node stays on its kubelet version until its
     * node group is updated, which replaces the node with one of a new identity on the new version.
     * https://docs.aws.amazon.com/eks/latest/userguide/update-cluster.html
     * https://docs.aws.amazon.com/eks/latest/userguide/managed-node-update-behavior.html
     */
    @Test
    void clusterAndNodegroupMoveOneMinorVersionUpKeepingTheirData() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "upgrade-" + suffix;
        String nodegroupName = "workers-" + suffix;
        given().contentType(JSON)
                .body("{\"name\":\"" + clusterName + "\",\"version\":\"1.33\","
                        + "\"roleArn\":\"arn:aws:iam::000000000000:role/eks\"}")
                .when().post("/clusters")
                .then().statusCode(200);
        cleanup.register(() -> {
            try {
                eksService.deleteNodeGroup(clusterName, nodegroupName);
                long deadline = System.currentTimeMillis() + 120_000;
                while (!eksService.listNodeGroups(clusterName).isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(2000);
                }
            } catch (RuntimeException ignored) {
                // Already gone, or never created.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            eksService.deleteCluster(clusterName);
        });
        Cluster cluster = waitForActive(clusterName);
        String endpoint = cluster.getEndpoint();
        assertEquals(0, kubectl(cluster, "create", "configmap", "survives", "--from-literal=key=value").exitCode());
        given().contentType(JSON)
                .body("{\"nodegroupName\":\"" + nodegroupName + "\",\"subnets\":[\"subnet-12345678\"],"
                        + "\"instanceTypes\":[\"m5.large\"],\"nodeRole\":\"arn:aws:iam::000000000000:role/eks-node\"}")
                .when().post("/clusters/" + clusterName + "/node-groups")
                .then().statusCode(200);
        waitForNodegroup(clusterName, nodegroupName);
        JsonNode original = waitForNode(cluster, "v1.33.");
        String originalName = original.path("metadata").path("name").asText();

        given().contentType(JSON).body("{\"version\":\"1.35\"}")
                .when().post("/clusters/" + clusterName + "/updates")
                .then().statusCode(400).body("message", equalTo("Unsupported Kubernetes minor version update from 1.33 to 1.35"));

        String updateId = given().contentType(JSON).body("{\"version\":\"1.34\"}")
                .when().post("/clusters/" + clusterName + "/updates")
                .then().statusCode(200)
                .body("update.type", equalTo("VersionUpdate"))
                .body("update.status", equalTo("InProgress"))
                .extract().path("update.id");
        given().when().get("/clusters/" + clusterName)
                .then().statusCode(200).body("cluster.status", equalTo("UPDATING"));
        waitForUpdate(clusterName, updateId, null);

        given().when().get("/clusters/" + clusterName)
                .then().statusCode(200)
                .body("cluster.status", equalTo("ACTIVE"))
                .body("cluster.version", equalTo("1.34"))
                .body("cluster.endpoint", equalTo(endpoint));
        cluster = eksService.describeCluster(clusterName);
        assertEquals(0, kubectl(cluster, "get", "configmap", "survives").exitCode(), "data must survive the update");
        assertTrue(kubectl(cluster, "version", "-o", "json").stdout().contains("\"minor\": \"34\""),
                "the API server runs the new version");
        JsonNode kept = waitForNode(cluster, "v1.33.");
        assertEquals(originalName, kept.path("metadata").path("name").asText(), "the node stays as it was");
        assertTrue(label(kept, "eks.amazonaws.com/nodegroup-image").startsWith("ami-"));

        given().contentType(JSON).body("{\"version\":\"1.35\"}")
                .when().post("/clusters/" + clusterName + "/node-groups/" + nodegroupName + "/update-version")
                .then().statusCode(400);
        String nodegroupUpdate = given().contentType(JSON).body("{}")
                .when().post("/clusters/" + clusterName + "/node-groups/" + nodegroupName + "/update-version")
                .then().statusCode(200)
                .body("update.type", equalTo("VersionUpdate"))
                .extract().path("update.id");
        waitForUpdate(clusterName, nodegroupUpdate, nodegroupName);
        given().when().get("/clusters/" + clusterName + "/node-groups/" + nodegroupName)
                .then().statusCode(200)
                .body("nodegroup.status", equalTo("ACTIVE"))
                .body("nodegroup.version", equalTo("1.34"))
                .body("nodegroup.releaseVersion", equalTo(EksNodeLayout.releaseVersion("1.34")));
        JsonNode replaced = waitForNode(cluster, "v1.34.");
        assertNotEquals(originalName, replaced.path("metadata").path("name").asText(),
                "the update replaces the node with a new instance");
        given().when().get("/clusters/" + clusterName + "/updates")
                .then().statusCode(200).body("updateIds", equalTo(List.of(updateId)));
    }

    private void waitForNodegroup(String clusterName, String nodegroupName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < deadline) {
            String status = given().when().get("/clusters/" + clusterName + "/node-groups/" + nodegroupName)
                    .then().statusCode(200).extract().path("nodegroup.status");
            if ("ACTIVE".equals(status)) {
                return;
            }
            Thread.sleep(3000);
        }
        fail("Node group " + nodegroupName + " did not become ACTIVE");
    }

    private Cluster waitForActive(String clusterName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            Cluster cluster = eksService.describeCluster(clusterName);
            if (cluster.getStatus() == ClusterStatus.ACTIVE && cluster.getContainerId() != null
                    && !cluster.isFinalizePending()) {
                return cluster;
            }
            Thread.sleep(2000);
        }
        fail("Cluster " + clusterName + " did not become ACTIVE");
        return null;
    }

    private void waitForUpdate(String clusterName, String updateId, String nodegroupName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 360_000;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            ValidatableResponse response = (nodegroupName == null ? given() : given().queryParam("nodegroupName", nodegroupName))
                    .when().get("/clusters/" + clusterName + "/updates/" + updateId)
                    .then().statusCode(200);
            status = response.extract().path("update.status");
            if ("Successful".equals(status)) {
                return;
            }
            if (!"InProgress".equals(status)) {
                fail("Update " + updateId + " ended " + status + ": " + response.extract().asString());
            }
            Thread.sleep(3000);
        }
        fail("Update " + updateId + " is still " + status);
    }

    private JsonNode waitForNode(Cluster cluster, String kubeletPrefix) throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            Cluster current = eksService.describeCluster(cluster.getName());
            ContainerExec.Result result = kubectl(current, "get", "nodes", "-o", "json");
            if (result.exitCode() == 0) {
                JsonNode items = MAPPER.readTree(result.stdout()).path("items");
                if (items.size() == 1) {
                    last = items.get(0);
                    boolean ready = false;
                    for (JsonNode condition : last.path("status").path("conditions")) {
                        ready |= "Ready".equals(condition.path("type").asText())
                                && "True".equals(condition.path("status").asText());
                    }
                    if (!ready) {
                        Thread.sleep(2000);
                        continue;
                    }
                    if (last.path("status").path("nodeInfo").path("kubeletVersion").asText().startsWith(kubeletPrefix)) {
                        return last;
                    }
                }
            }
            Thread.sleep(2000);
        }
        fail("The node did not run kubelet " + kubeletPrefix + "; last seen: " + last);
        return null;
    }

    private ContainerExec.Result kubectl(Cluster cluster, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "kubectl";
        System.arraycopy(args, 0, command, 1, args.length);
        return ContainerExec.run(dockerClient, cluster.getContainerId(), command, 30);
    }

    private static String label(JsonNode node, String key) {
        JsonNode value = node.path("metadata").path("labels").get(key);
        return value == null ? null : value.asText();
    }
}
