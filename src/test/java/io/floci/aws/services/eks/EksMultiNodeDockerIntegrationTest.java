package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.testing.EksMultiNodeDockerProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A multi-node cluster end to end: an agentless control plane with no node until a node group exists, then one k3s
 * agent per managed node, named and labelled as EKS names and labels managed nodes, carrying pod traffic between
 * nodes, scaled under the node cap, and removed with its node group and its cluster.
 */
@QuarkusTest
@TestProfile(EksMultiNodeDockerProfile.class)
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class EksMultiNodeDockerIntegrationTest {

    private static final String JSON = "application/json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUBNET_A = "subnet-default-us-east-1-a";
    private static final String SUBNET_B = "subnet-default-us-east-1-b";

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
        Assumptions.assumeTrue(available, "Docker daemon must be available for EKS multi-node tests");
    }

    @Test
    void nodeGroupsRunAsAgentsNamedAndLabelledAsManagedNodes() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String clusterName = "multi-" + suffix;

        given().contentType(JSON)
                .body("""
                        {"name": "%s", "version": "1.33", "roleArn": "arn:aws:iam::000000000000:role/eks",
                         "resourcesVpcConfig": {"subnetIds": ["%s", "%s"]}}
                        """.formatted(clusterName, SUBNET_A, SUBNET_B))
                .when().post("/clusters")
                .then().statusCode(200);
        cleanup.register(() -> teardown(clusterName));
        Cluster cluster = waitForCluster(clusterName);

        Map<String, String> serverLabels = dockerClient.inspectContainerCmd(cluster.getContainerId()).exec()
                .getConfig().getLabels();
        assertEquals("control-plane", serverLabels.get("io.floci.eks.role"));
        assertEquals("agentless", serverLabels.get("io.floci.eks.topology"));
        assertEquals(0, nodes(cluster).size(), "a cluster with no node group has no node");
        String corednsPhase = waitFor(() -> {
            JsonNode pods = kubectlJson(cluster, "-n", "kube-system", "get", "pods", "-l", "k8s-app=kube-dns");
            return pods.path("items").isEmpty() ? null : pods.path("items").get(0).path("status").path("phase").asText();
        }, "the CoreDNS pod");
        assertEquals("Pending", corednsPhase, "CoreDNS waits for a node");

        createNodegroup(clusterName, "general", "{\"minSize\":0,\"maxSize\":8,\"desiredSize\":2}",
                "[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]", "[\"m5.large\"]", "");
        createNodegroup(clusterName, "tainted", "{\"minSize\":0,\"maxSize\":2,\"desiredSize\":1}",
                "[\"" + SUBNET_A + "\"]", "[\"g5.xlarge\"]",
                ",\"taints\":[{\"key\":\"nvidia.com/gpu\",\"effect\":\"NO_SCHEDULE\"}],"
                        + "\"labels\":{\"team\":\"batch\"}");
        waitForNodegroup(clusterName, "general", "ACTIVE");
        waitForNodegroup(clusterName, "tainted", "ACTIVE");

        List<JsonNode> nodes = waitForNodes(cluster, list -> list.size() == 3);
        for (JsonNode node : nodes) {
            String name = node.path("metadata").path("name").asText();
            assertTrue(name.matches("ip-172-31-\\d+-\\d+\\.ec2\\.internal"), name);
            assertTrue(node.path("spec").path("providerID").asText().matches("aws:///us-east-1[ab]/i-[0-9a-f]{17}"),
                    node.path("spec").path("providerID").asText());
            String internalIp = node.path("status").path("addresses").get(0).path("address").asText();
            assertEquals("ip-" + internalIp.replace('.', '-') + ".ec2.internal", name);
            assertTrue(label(node, "topology.k8s.aws/zone-id").matches("use1-az[12]"));
            assertTrue(label(node, "eks.amazonaws.com/nodegroup-image").startsWith("ami-"));
        }
        String resolvConf = ContainerExec.run(dockerClient, agents(cluster).getFirst().getId(),
                new String[] {"cat", "/etc/resolv.conf"}, 30).stdout();
        assertTrue(resolvConf.contains("search ec2.internal") && resolvConf.contains("ndots:1"),
                "a node resolves as an instance with the VPC's default DHCP options: " + resolvConf);
        List<JsonNode> general = nodes.stream().filter(n -> "general".equals(label(n, "eks.amazonaws.com/nodegroup")))
                .toList();
        assertEquals(2, general.size());
        assertEquals(List.of("us-east-1a", "us-east-1b"), general.stream()
                .map(n -> label(n, "topology.kubernetes.io/zone")).sorted().toList(), "nodes spread over the subnets");
        for (JsonNode node : general) {
            assertEquals("m5.large", label(node, "node.kubernetes.io/instance-type"));
            assertEquals("29", node.path("status").path("capacity").path("pods").asText(), "m5.large max pods");
        }
        JsonNode tainted = nodes.stream().filter(n -> "tainted".equals(label(n, "eks.amazonaws.com/nodegroup")))
                .findFirst().orElseThrow();
        assertEquals("batch", label(tainted, "team"));
        assertEquals("g5.xlarge", label(tainted, "node.kubernetes.io/instance-type"));
        assertEquals("58", tainted.path("status").path("capacity").path("pods").asText(), "g5.xlarge max pods");
        assertTrue(tainted.path("spec").path("taints").toString().contains("\"nvidia.com/gpu\""));
        String gpuNode = tainted.path("metadata").path("name").asText();
        String gpus = waitFor(() -> {
            String value = kubectlJson(cluster, "get", "node", gpuNode).path("status").path("allocatable")
                    .path("nvidia.com/gpu").asText("");
            return value.isEmpty() ? null : value;
        }, "nvidia.com/gpu on the GPU node");
        assertEquals("1", gpus, "a g5.xlarge advertises its one GPU");

        // The three pods start together, so each node pulls its image while the others do.
        String webNode = general.get(0).path("metadata").path("name").asText();
        String probeNode = general.get(1).path("metadata").path("name").asText();
        kubectl(cluster, "run", "web", "--image=" + TestImages.BUSYBOX, "--restart=Never", "--labels=app=web",
                "--overrides={\"spec\":{\"nodeName\":\"" + webNode + "\"}}", "--",
                "sh", "-c", "mkdir -p /www && echo cross-node > /www/index.html && exec httpd -f -p 8080 -h /www");
        kubectl(cluster, "expose", "pod", "web", "--port=8080");
        kubectl(cluster, "run", "probe", "--image=" + TestImages.BUSYBOX, "--restart=Never",
                "--overrides={\"spec\":{\"nodeName\":\"" + probeNode + "\"}}", "--",
                "sh", "-c", "for i in $(seq 1 120); do wget -q -O- -T 2 http://web:8080/ && exit 0; sleep 2; done; exit 1");
        kubectl(cluster, "run", "cuda", "--image=" + TestImages.BUSYBOX, "--restart=Never",
                "--overrides={\"spec\":{\"containers\":[{\"name\":\"cuda\",\"image\":\"" + TestImages.BUSYBOX
                        + "\",\"command\":[\"echo\",\"gpu\"],\"resources\":{\"limits\":{\"nvidia.com/gpu\":\"1\"}}}]}}");
        String phase = waitFor(() -> {
            String value = kubectlJson(cluster, "get", "pod", "probe").path("status").path("phase").asText();
            return "Succeeded".equals(value) || "Failed".equals(value) ? value : null;
        }, "the probe pod to finish");
        assertEquals("Succeeded", phase, "a pod on one node reaches a pod on another, through a Service");
        assertTrue(kubectl(cluster, "logs", "probe").contains("cross-node"), "kubectl logs reaches the agent");
        String gpuPhase = waitFor(() -> {
            JsonNode pod = kubectlJson(cluster, "get", "pod", "cuda");
            String value = pod.path("status").path("phase").asText();
            return "Succeeded".equals(value) || "Failed".equals(value)
                    ? value + "@" + pod.path("spec").path("nodeName").asText() : null;
        }, "the GPU pod to finish");
        assertEquals("Succeeded@" + gpuNode, gpuPhase,
                "a pod requesting a GPU tolerates the GPU taint (ExtendedResourceToleration) and runs on the GPU node");

        scale(clusterName, "general", 3);
        waitForNodegroup(clusterName, "general", "ACTIVE");
        waitForNodes(cluster, list -> list.size() == 4);
        assertEquals(4, agents(cluster).size());

        scale(clusterName, "general", 6);
        waitForNodegroup(clusterName, "general", "ACTIVE");
        given().when().get("/clusters/" + clusterName + "/node-groups/general")
                .then().statusCode(200).body("nodegroup.scalingConfig.desiredSize", equalTo(6))
                .body("nodegroup.nodes", nullValue());
        waitForNodes(cluster, list -> list.size() == 5);
        assertEquals(5, agents(cluster).size(), "the cap of five nodes holds");

        given().when().delete("/clusters/" + clusterName + "/node-groups/tainted")
                .then().statusCode(200).body("nodegroup.status", equalTo("DELETING"));
        waitForNodegroupGone(clusterName, "tainted");
        List<JsonNode> afterDelete = waitForNodes(cluster, list -> list.stream()
                .noneMatch(n -> "tainted".equals(label(n, "eks.amazonaws.com/nodegroup"))) && list.size() == 5);
        assertTrue(agents(cluster).stream().noneMatch(c -> "tainted".equals(c.getLabels().get("io.floci.eks.nodegroup"))));
        assertEquals(5, afterDelete.size(), "the general node group takes the freed slot");

        given().when().delete("/clusters/" + clusterName + "/node-groups/general").then().statusCode(200);
        waitForNodegroupGone(clusterName, "general");
        assertTrue(agents(cluster).isEmpty());

        String network = cluster.getNodeNetwork();
        String server = cluster.getContainerId();
        given().when().delete("/clusters/" + clusterName).then().statusCode(200);
        assertTrue(dockerClient.listContainersCmd().withShowAll(true).exec().stream()
                .noneMatch(c -> server.equals(c.getId())), "the server is removed");
        assertTrue(dockerClient.listNetworksCmd().withNameFilter(network).exec().stream()
                .noneMatch(n -> network.equals(n.getName())), "the cluster network is removed");
    }

    /** Node groups first, then the cluster, as EKS requires; whatever the test left. */
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

    private void createNodegroup(String clusterName, String name, String scaling, String subnets, String types,
                                 String extra) {
        given().contentType(JSON)
                .body("{\"nodegroupName\":\"" + name + "\",\"subnets\":" + subnets
                        + ",\"nodeRole\":\"arn:aws:iam::000000000000:role/eks-node\",\"scalingConfig\":" + scaling
                        + ",\"instanceTypes\":" + types + extra + "}")
                .when().post("/clusters/" + clusterName + "/node-groups")
                .then().statusCode(200).body("nodegroup.status", equalTo("CREATING"));
    }

    private void scale(String clusterName, String name, int desired) {
        given().contentType(JSON)
                .body("{\"scalingConfig\":{\"desiredSize\":" + desired + "}}")
                .when().post("/clusters/" + clusterName + "/node-groups/" + name + "/update-config")
                .then().statusCode(200);
    }

    private List<Container> agents(Cluster cluster) {
        return dockerClient.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("io.floci.eks.cluster", cluster.getDockerName(), "io.floci.eks.role", "node"))
                .exec();
    }

    private Cluster waitForCluster(String clusterName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            Cluster cluster = eksService.describeCluster(clusterName);
            if (cluster.getStatus() == ClusterStatus.ACTIVE && cluster.getJoinToken() != null) {
                return cluster;
            }
            Thread.sleep(2000);
        }
        fail("Cluster " + clusterName + " did not become ACTIVE");
        return null;
    }

    private void waitForNodegroup(String clusterName, String name, String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 420_000;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = given().when().get("/clusters/" + clusterName + "/node-groups/" + name)
                    .then().statusCode(200).extract().path("nodegroup.status");
            if (status.equals(last)) {
                return;
            }
            assertFalse("CREATE_FAILED".equals(last), "node group " + name + " failed");
            Thread.sleep(3000);
        }
        fail("Node group " + name + " did not become " + status + "; last " + last);
    }

    private void waitForNodegroupGone(String clusterName, String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            int status = given().when().get("/clusters/" + clusterName + "/node-groups/" + name)
                    .then().extract().statusCode();
            if (status == 404) {
                return;
            }
            Thread.sleep(2000);
        }
        fail("Node group " + name + " was not deleted");
    }

    private List<JsonNode> waitForNodes(Cluster cluster, Predicate<List<JsonNode>> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 300_000;
        List<JsonNode> last = List.of();
        while (System.currentTimeMillis() < deadline) {
            last = nodes(cluster);
            if (condition.test(last) && last.stream().allMatch(EksMultiNodeDockerIntegrationTest::ready)) {
                return last;
            }
            Thread.sleep(3000);
        }
        fail("Nodes did not reach the expected state; last seen " + last.stream()
                .map(n -> n.path("metadata").path("name").asText()).toList());
        return last;
    }

    private List<JsonNode> nodes(Cluster cluster) throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        kubectlJson(cluster, "get", "nodes").path("items").forEach(nodes::add);
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

    private JsonNode kubectlJson(Cluster cluster, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(args));
        command.add("-o");
        command.add("json");
        return MAPPER.readTree(kubectl(cluster, command.toArray(String[]::new)));
    }

    private String kubectl(Cluster cluster, String... args) {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        command.addAll(List.of(args));
        ContainerExec.Result result = ContainerExec.run(dockerClient, cluster.getContainerId(),
                command.toArray(String[]::new), 60);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("kubectl " + String.join(" ", args) + " failed: " + result.summary());
        }
        return result.stdout();
    }

    private interface Probe<T> {
        T get() throws Exception;
    }

    private static <T> T waitFor(Probe<T> probe, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                T value = probe.get();
                if (value != null) {
                    return value;
                }
            } catch (IllegalStateException expected) {
                // Safe to retry: kubectl fails while the pod is still being created.
            }
            Thread.sleep(2000);
        }
        fail("Timed out waiting for " + what);
        return null;
    }

    private static String label(JsonNode node, String key) {
        JsonNode value = node.path("metadata").path("labels").get(key);
        return value == null ? null : value.asText();
    }
}
