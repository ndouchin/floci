package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Services of type LoadBalancer on a multi-node cluster: the EKS-shaped DNS name in the status, internal and
 * Load Balancer Controller variants, a status servicelb no longer rewrites, traffic from the Docker host through the
 * published port and from a pod through the DNS name, and the appliance gone with its Service and its cluster.
 */
@QuarkusTest
@TestProfile(EksMultiNodeDockerProfile.class)
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class EksLoadBalancerDockerIntegrationTest {

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
        Assumptions.assumeTrue(available, "Docker daemon must be available for EKS load balancer tests");
    }

    @Test
    void loadBalancerServicesGetAnEksHostnameAndForwardToTheirPods() throws Exception {
        String clusterName = "lb-" + UUID.randomUUID().toString().substring(0, 6);
        given().contentType(JSON)
                .body("{\"name\":\"" + clusterName + "\",\"version\":\"1.33\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks\"}")
                .when().post("/clusters").then().statusCode(200);
        cleanup.register(() -> EksDockerNodegroups.teardown(eksService, clusterName));
        Cluster cluster = waitForActive(clusterName);
        given().contentType(JSON)
                .body("{\"nodegroupName\":\"workers\",\"subnets\":[\"subnet-12345678\"],\"instanceTypes\":[\"m5.large\"],"
                        + "\"nodeRole\":\"arn:aws:iam::000000000000:role/eks-node\","
                        + "\"scalingConfig\":{\"minSize\":0,\"maxSize\":2,\"desiredSize\":2}}")
                .when().post("/clusters/" + clusterName + "/node-groups").then().statusCode(200);
        EksDockerNodegroups.awaitActive(clusterName, "workers");

        kubectl(cluster, "create", "deployment", "web", "--image=" + TestImages.BUSYBOX, "--replicas=2", "--",
                "sh", "-c", "mkdir -p /www && echo lb-ok > /www/index.html && exec httpd -f -p 8080 -h /www");
        kubectl(cluster, "expose", "deployment", "web", "--type=LoadBalancer", "--port=80", "--target-port=8080");
        kubectl(cluster, "expose", "deployment", "web", "--name=web-internal", "--type=LoadBalancer", "--port=80",
                "--target-port=8080", "--overrides={\"metadata\":{\"annotations\":"
                        + "{\"service.beta.kubernetes.io/aws-load-balancer-internal\":\"true\"}}}");
        kubectl(cluster, "expose", "deployment", "web", "--name=web-nlb", "--type=LoadBalancer", "--port=80",
                "--target-port=8080", "--overrides={\"spec\":{\"loadBalancerClass\":\"service.k8s.aws/nlb\"}}");

        String hostname = waitForHostname(cluster, "web");
        assertTrue(hostname.matches("a[0-9a-f]{31}-\\d{10}\\.us-east-1\\.elb\\.amazonaws\\.com"), hostname);
        assertTrue(waitForHostname(cluster, "web-internal").startsWith("internal-a"));
        assertTrue(waitForHostname(cluster, "web-nlb").matches("k8s-default-webnlb-[0-9a-f]{10}-[0-9a-f]{16}\\.elb\\.us-east-1\\.amazonaws\\.com"));
        JsonNode nlb = service(cluster, "web-nlb").path("status").path("loadBalancer").path("ingress");
        assertEquals(80, nlb.get(0).path("ports").get(0).path("port").asInt(), "the controller NLB lists its ports");

        JsonNode web = service(cluster, "web");
        assertTrue(web.path("metadata").path("finalizers").toString().contains("service.kubernetes.io/load-balancer-cleanup"));
        assertTrue(kubectl(cluster, "get", "events", "--field-selector", "involvedObject.name=web", "-o", "json")
                .contains("EnsuredLoadBalancer"));

        kubectl(cluster, "rollout", "status", "deployment/web", "--timeout=300s");
        kubectl(cluster, "scale", "deployment", "web", "--replicas=1");
        Thread.sleep(10_000);
        assertEquals(hostname, waitForHostname(cluster, "web"), "nothing rewrites the status after a scale");

        String hostPorts = service(cluster, "web").path("metadata").path("annotations")
                .path("floci.io/load-balancer-host-ports").asText();
        assertTrue(hostPorts.matches("TCP/80=127\\.0\\.0\\.1:\\d+"), hostPorts);
        String body = httpGet("http://" + hostPorts.substring(hostPorts.indexOf('=') + 1) + "/");
        assertEquals("lb-ok", body.trim(), "the Docker host reaches the Service through the published port");

        kubectl(cluster, "run", "probe", "--image=" + TestImages.BUSYBOX, "--restart=Never", "--",
                "sh", "-c", "for i in $(seq 1 90); do wget -q -O- -T 2 http://"
                        + hostname + "/ && exit 0; sleep 2; done; exit 1");
        String probe = waitForPod(cluster, "probe");
        assertEquals("Succeeded", probe, "a pod reaches the load balancer by its DNS name: "
                + kubectl(cluster, "logs", "probe"));
        assertTrue(kubectl(cluster, "logs", "probe").contains("lb-ok"));

        String uid = web.path("metadata").path("uid").asText();
        assertEquals(1, appliancesOf(cluster, uid).size(), "one appliance stands for the Service's load balancer");
        kubectl(cluster, "delete", "service", "web", "--timeout=60s");
        long deadline = System.currentTimeMillis() + 60_000;
        while (!appliancesOf(cluster, uid).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2000);
        }
        assertTrue(appliancesOf(cluster, uid).isEmpty(), "the appliance goes with its Service");

        EksDockerNodegroups.teardown(eksService, clusterName);
        assertTrue(appliances(cluster).isEmpty(), "every appliance goes with the cluster");
    }

    private List<String> appliancesOf(Cluster cluster, String serviceUid) {
        List<String> names = new ArrayList<>();
        dockerClient.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("io.floci.eks.cluster", cluster.getDockerName(),
                        "io.floci.eks.load-balancer", serviceUid))
                .exec().forEach(container -> names.add(container.getNames()[0].substring(1)));
        return names;
    }

    private List<String> appliances(Cluster cluster) {
        List<String> names = new ArrayList<>();
        dockerClient.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("io.floci.eks.cluster", cluster.getDockerName(), "io.floci.eks.role", "load-balancer"))
                .exec().forEach(container -> names.add(container.getNames()[0].substring(1)));
        return names;
    }

    private Cluster waitForActive(String clusterName) throws InterruptedException {
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

    private String waitForHostname(Cluster cluster, String name) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode ingress = service(cluster, name).path("status").path("loadBalancer").path("ingress");
            if (ingress.isArray() && !ingress.isEmpty() && ingress.get(0).hasNonNull("hostname")) {
                return ingress.get(0).path("hostname").asText();
            }
            Thread.sleep(2000);
        }
        fail("Service " + name + " got no load balancer hostname");
        return null;
    }

    private String waitForPod(Cluster cluster, String name) throws Exception {
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            String phase = MAPPER.readTree(kubectl(cluster, "get", "pod", name, "-o", "json"))
                    .path("status").path("phase").asText();
            if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                return phase;
            }
            Thread.sleep(2000);
        }
        fail("Pod " + name + " did not finish");
        return null;
    }

    private JsonNode service(Cluster cluster, String name) throws Exception {
        return MAPPER.readTree(kubectl(cluster, "get", "service", name, "-o", "json"));
    }

    private static String httpGet(String url) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            long deadline = System.currentTimeMillis() + 120_000;
            Exception last = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        return response.body();
                    }
                } catch (Exception e) {
                    last = e;
                }
                Thread.sleep(2000);
            }
            throw new AssertionError("No HTTP 200 from " + url, last);
        }
    }

    private String kubectl(Cluster cluster, String... args) {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        command.addAll(List.of(args));
        ContainerExec.Result result = ContainerExec.run(dockerClient, cluster.getContainerId(),
                command.toArray(String[]::new), 320);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("kubectl " + String.join(" ", args) + " failed: " + result.summary());
        }
        return result.stdout();
    }
}
