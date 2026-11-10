package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.testing.EksRuntimeDockerProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives one real k3s cluster through the EKS API and checks what a managed node group puts on
 * the node it runs on: its labels and taints, kept current by UpdateNodegroupConfig.
 */
@QuarkusTest
@TestProfile(EksRuntimeDockerProfile.class)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class EksNodegroupNodeDockerIntegrationTest {

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
        Assumptions.assumeTrue(available, "Docker daemon must be available for EKS node group tests");
    }

    @Test
    void firstNodegroupLabelsAndTaintsReachTheRunningNode() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "ng-node-" + suffix;
        String nodegroupName = "workers-" + suffix;

        given().contentType(JSON)
                .body("{\"name\":\"" + clusterName + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks\"}")
                .when().post("/clusters")
                .then().statusCode(200);
        cleanup.register(() -> eksService.deleteCluster(clusterName));
        waitForClusterStatus(clusterName, ClusterStatus.ACTIVE);

        given().contentType(JSON)
                .body("""
                        {
                          "nodegroupName": "%s",
                          "subnets": ["subnet-12345678"],
                          "nodeRole": "arn:aws:iam::000000000000:role/eks-node",
                          "labels": {"team": "payments"},
                          "taints": [{"key": "dedicated", "value": "batch", "effect": "NO_SCHEDULE"}]
                        }
                        """.formatted(nodegroupName))
                .when().post("/clusters/" + clusterName + "/node-groups")
                .then().statusCode(200);
        cleanup.register(() -> eksService.deleteNodeGroup(clusterName, nodegroupName));

        JsonNode node = waitForNode(clusterName, n -> "payments".equals(label(n, "team"))
                && hasTaint(n, "dedicated", "batch", "NoSchedule"));
        assertEquals(nodegroupName, label(node, "eks.amazonaws.com/nodegroup"));
        assertEquals("ON_DEMAND", label(node, "eks.amazonaws.com/capacityType"));
        assertEquals("t3.medium", label(node, "node.kubernetes.io/instance-type"));
    }

    private void waitForClusterStatus(String clusterName, ClusterStatus status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            Cluster cluster = eksService.describeCluster(clusterName);
            if (cluster.getStatus() == status && !cluster.isFinalizePending()) {
                return;
            }
            Thread.sleep(2000);
        }
        fail("Cluster " + clusterName + " did not reach " + status);
    }

    private JsonNode waitForNode(String clusterName, Predicate<JsonNode> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 180_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            Cluster cluster = eksService.describeCluster(clusterName);
            if (cluster.getContainerId() != null) {
                ContainerExec.Result result = ContainerExec.run(dockerClient, cluster.getContainerId(),
                        new String[]{"kubectl", "get", "nodes", "-o", "json"}, 20);
                if (result.exitCode() == 0) {
                    JsonNode items = MAPPER.readTree(result.stdout()).path("items");
                    if (items.size() == 1) {
                        last = items.get(0);
                        if (condition.test(last)) {
                            return last;
                        }
                    }
                }
            }
            Thread.sleep(2000);
        }
        fail("Node did not reach the expected state; last seen: " + last);
        return null;
    }

    private static String label(JsonNode node, String key) {
        JsonNode value = node.path("metadata").path("labels").get(key);
        return value == null ? null : value.asText();
    }

    private static boolean hasTaint(JsonNode node, String key, String value, String effect) {
        for (JsonNode taint : node.path("spec").path("taints")) {
            Map<String, String> fields = new HashMap<>();
            taint.fields().forEachRemaining(field -> fields.put(field.getKey(), field.getValue().asText()));
            if (key.equals(fields.get("key")) && effect.equals(fields.get("effect"))
                    && (value == null ? !fields.containsKey("value") : value.equals(fields.get("value")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTaintKey(JsonNode node, String key) {
        for (JsonNode taint : node.path("spec").path("taints")) {
            if (key.equals(taint.path("key").asText())) {
                return true;
            }
        }
        return false;
    }

    static void assertNoTaint(JsonNode node, String key) {
        assertTrue(!hasTaintKey(node, key), "taint " + key + " must be gone: " + node.path("spec"));
    }
}
