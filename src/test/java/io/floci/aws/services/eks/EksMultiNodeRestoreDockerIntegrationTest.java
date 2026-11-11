package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What survives on a multi-node cluster: an agent that died or was removed comes back under the same name on its
 * volume, and a restarted Floci adopts the server and the agents it finds. A cluster stored by round 1, which has no
 * topology, is adopted as the single-node cluster it was.
 */
@QuarkusTest
@TestProfile(EksRuntimeDockerProfile.class)
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class EksMultiNodeRestoreDockerIntegrationTest {

    private static final String JSON = "application/json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    DockerClient dockerClient;

    @Inject
    EksService eksService;

    @Inject
    EksClusterManager clusterManager;

    @BeforeEach
    void requireDocker() {
        boolean available;
        try {
            dockerClient.pingCmd().exec();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for EKS restore tests");
    }

    @Test
    void agentsComeBackUnderTheirNamesAndARestartAdoptsServerAndAgents() throws Exception {
        String clusterName = "restore-" + UUID.randomUUID().toString().substring(0, 6);
        given().contentType(JSON)
                .body("{\"name\":\"" + clusterName + "\",\"version\":\"1.33\",\"roleArn\":\"arn:aws:iam::"
                        + ACCOUNT + ":role/eks\"}")
                .when().post("/clusters").then().statusCode(200);
        cleanup.register(() -> teardown(clusterName));
        Cluster cluster = waitForCluster(clusterName, c -> c.getJoinToken() != null);
        given().contentType(JSON)
                .body("{\"nodegroupName\":\"workers\",\"subnets\":[\"subnet-12345678\"],\"instanceTypes\":[\"m5.large\"],"
                        + "\"nodeRole\":\"arn:aws:iam::" + ACCOUNT + ":role/eks-node\","
                        + "\"scalingConfig\":{\"minSize\":0,\"maxSize\":3,\"desiredSize\":2}}")
                .when().post("/clusters/" + clusterName + "/node-groups").then().statusCode(200);
        waitForNodegroup(clusterName, "ACTIVE");
        List<String> names = nodeNames(waitForNodes(cluster, 2));
        ContainerExec.Result awsAuth = kubectl(cluster, "-n", "kube-system", "get", "configmap", "aws-auth", "-o", "json");
        assertEquals(0, awsAuth.exitCode(), "EKS writes aws-auth for a managed node group in a CONFIG_MAP cluster");
        String mapRoles = MAPPER.readTree(awsAuth.stdout()).path("data").path("mapRoles").asText();
        assertTrue(mapRoles.contains("rolearn: arn:aws:iam::" + ACCOUNT + ":role/eks-node"), mapRoles);
        assertTrue(mapRoles.contains("system:node:{{EC2PrivateDNSName}}"), mapRoles);
        assertTrue(mapRoles.contains("system:bootstrappers"), mapRoles);
        assertEquals(0, kubectl(cluster, "create", "configmap", "survives", "--from-literal=key=value").exitCode());

        List<Container> agents = agents(cluster);
        Container removed = agents.get(0);
        Container stopped = agents.get(1);
        dockerClient.removeContainerCmd(removed.getId()).withForce(true).withRemoveVolumes(true).exec();
        dockerClient.stopContainerCmd(stopped.getId()).withTimeout(5).exec();
        waitForAgents(cluster, list -> list.size() == 2 && list.stream().allMatch(c -> "running".equals(c.getState()))
                && list.stream().anyMatch(c -> c.getNames()[0].equals(removed.getNames()[0])
                        && !c.getId().equals(removed.getId())));
        assertEquals(names, nodeNames(waitForNodes(cluster, 2)), "a recreated agent rejoins under its name");

        clusterManager.detachCluster(cluster);
        dockerClient.stopContainerCmd(cluster.getContainerId()).withTimeout(5).exec();
        cluster.setContainerId(null);
        cluster.setStatus(ClusterStatus.CREATING);
        clusterManager.restoreCluster(cluster);
        eksService.putClusterForAccount(ACCOUNT, cluster);
        cluster = waitForCluster(clusterName, c -> c.getContainerId() != null);
        assertEquals(names, nodeNames(waitForNodes(cluster, 2)), "a restart adopts the agents");
        assertEquals(0, kubectl(cluster, "get", "configmap", "survives").exitCode(), "the server keeps its data");
        assertEquals(2, agents(cluster).size());
    }

    @Test
    void clusterStoredByRoundOneKeepsItsSingleNode() throws Exception {
        String clusterName = "legacy-" + UUID.randomUUID().toString().substring(0, 6);
        Cluster legacy = new Cluster();
        legacy.setName(clusterName);
        legacy.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/" + clusterName);
        legacy.setAccountId(ACCOUNT);
        legacy.setVersion("1.33");
        legacy.setExplicitVersion(true);
        legacy.setCreatedAt(Instant.now());
        legacy.setLogging(EksService.defaultLogging());
        legacy.setStatus(ClusterStatus.CREATING);
        legacy.setMetricsServer(false);
        clusterManager.startCluster(legacy);
        eksService.putClusterForAccount(ACCOUNT, legacy);
        cleanup.register(() -> teardown(clusterName));
        Cluster cluster = waitForCluster(clusterName, c -> true);
        assertNull(cluster.getTopology());

        given().contentType(JSON)
                .body("{\"nodegroupName\":\"workers\",\"subnets\":[\"subnet-12345678\"],"
                        + "\"nodeRole\":\"arn:aws:iam::" + ACCOUNT + ":role/eks-node\",\"labels\":{\"team\":\"legacy\"}}")
                .when().post("/clusters/" + clusterName + "/node-groups").then().statusCode(200);
        waitForNodegroup(clusterName, "ACTIVE");

        List<JsonNode> nodes = waitForNodes(cluster, 1);
        JsonNode node = nodes.getFirst();
        assertTrue(node.path("metadata").path("name").asText().matches("i-[0-9a-f]{17}\\.ec2\\.internal"),
                "the round 1 node keeps its name");
        long deadline = System.currentTimeMillis() + 120_000;
        while (!"legacy".equals(node.path("metadata").path("labels").path("team").asText())) {
            if (System.currentTimeMillis() > deadline) {
                fail("the node group's labels did not reach the single node");
            }
            Thread.sleep(2000);
            node = waitForNodes(cluster, 1).getFirst();
        }
        assertTrue(agents(cluster).isEmpty(), "a round 1 cluster runs no agent");
    }

    private void teardown(String clusterName) {
        if (eksService.listClusters().stream().noneMatch(clusterName::equals)) {
            return;
        }
        for (String nodegroup : eksService.listNodeGroups(clusterName)) {
            eksService.deleteNodeGroup(clusterName, nodegroup);
        }
        long deadline = System.currentTimeMillis() + 180_000;
        try {
            while (!eksService.listNodeGroups(clusterName).isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        eksService.deleteCluster(clusterName);
    }

    private List<Container> agents(Cluster cluster) {
        return dockerClient.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("io.floci.eks.cluster", cluster.getDockerName(), "io.floci.eks.role", "node"))
                .exec();
    }

    private void waitForAgents(Cluster cluster, Predicate<List<Container>> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test(agents(cluster))) {
                return;
            }
            Thread.sleep(2000);
        }
        fail("The agents did not come back");
    }

    private Cluster waitForCluster(String clusterName, Predicate<Cluster> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            Cluster cluster = eksService.describeCluster(clusterName);
            if (cluster.getStatus() == ClusterStatus.ACTIVE && cluster.getContainerId() != null
                    && condition.test(cluster)) {
                return cluster;
            }
            Thread.sleep(2000);
        }
        fail("Cluster " + clusterName + " did not become ACTIVE");
        return null;
    }

    private void waitForNodegroup(String clusterName, String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < deadline) {
            String current = given().when().get("/clusters/" + clusterName + "/node-groups/workers")
                    .then().statusCode(200).extract().path("nodegroup.status");
            if (status.equals(current)) {
                return;
            }
            Thread.sleep(3000);
        }
        fail("Node group did not become " + status);
    }

    private List<JsonNode> waitForNodes(Cluster cluster, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 240_000;
        List<JsonNode> nodes = List.of();
        while (System.currentTimeMillis() < deadline) {
            Cluster current = eksService.describeCluster(cluster.getName());
            ContainerExec.Result result = kubectl(current, "get", "nodes", "-o", "json");
            if (result.exitCode() == 0) {
                List<JsonNode> read = new ArrayList<>();
                MAPPER.readTree(result.stdout()).path("items").forEach(read::add);
                nodes = read;
                if (nodes.size() == count && nodes.stream().allMatch(EksMultiNodeRestoreDockerIntegrationTest::ready)) {
                    return nodes;
                }
            }
            Thread.sleep(3000);
        }
        fail("Expected " + count + " Ready nodes; last saw " + nodeNames(nodes));
        return nodes;
    }

    private static boolean ready(JsonNode node) {
        for (JsonNode condition : node.path("status").path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                return "True".equals(condition.path("status").asText());
            }
        }
        return false;
    }

    private static List<String> nodeNames(List<JsonNode> nodes) {
        return nodes.stream().map(node -> node.path("metadata").path("name").asText()).sorted().toList();
    }

    private ContainerExec.Result kubectl(Cluster cluster, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "kubectl";
        System.arraycopy(args, 0, command, 1, args.length);
        return ContainerExec.run(dockerClient, eksService.describeCluster(cluster.getName()).getContainerId(),
                command, 30);
    }
}
