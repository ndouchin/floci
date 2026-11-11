package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A real k3s-backed cluster runs no metrics-server, as an EKS cluster, until the {@code metrics-server}
 * community add-on is created, and loses it again when the add-on is deleted.
 */
@QuarkusTest
@TestProfile(EksMetricsServerAddonDockerIntegrationTest.Profile.class)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class EksMetricsServerAddonDockerIntegrationTest {

    /** A profile of its own so the CI partitioner can place this class apart from the other cluster classes. */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.eks.mock", "false");
        }
    }

    private static final String JSON = "application/json";

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
        Assumptions.assumeTrue(available, "Docker daemon must be available for EKS add-on tests");
    }

    @Test
    void metricsServerRunsOnlyWhileItsAddonIsInstalled() throws Exception {
        String clusterName = "metrics-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType(JSON)
                .body("{\"name\":\"" + clusterName + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks\"}")
                .when().post("/clusters")
                .then().statusCode(200);
        cleanup.register(() -> eksService.deleteCluster(clusterName));
        Cluster cluster = waitForActive(clusterName);

        // k3s applies every bundled manifest in one pass, so once CoreDNS exists metrics-server would too.
        waitFor(cluster, "the CoreDNS deployment", "get", "deployment", "-n", "kube-system", "coredns");
        Thread.sleep(5000);
        assertNotEquals(0, kubectl(cluster, "get", "deployment", "-n", "kube-system", "metrics-server").exitCode(),
                "an EKS cluster ships no metrics-server");
        assertNotEquals(0, kubectl(cluster, "get", "apiservice", "v1beta1.metrics.k8s.io").exitCode());

        given().contentType(JSON).body("{\"addonName\":\"metrics-server\"}")
                .when().post("/clusters/" + clusterName + "/addons")
                .then().statusCode(200)
                .body("addon.addonName", equalTo("metrics-server"))
                .body("addon.owner", equalTo("community"))
                .body("addon.status", equalTo("ACTIVE"));
        waitFor(cluster, "the metrics API to be available", "wait", "--for=condition=Available", "--timeout=5s",
                "apiservice/v1beta1.metrics.k8s.io");

        given().when().delete("/clusters/" + clusterName + "/addons/metrics-server")
                .then().statusCode(200);
        waitForAbsent(cluster, "deployment", "-n", "kube-system", "metrics-server");
        waitForAbsent(cluster, "apiservice", "v1beta1.metrics.k8s.io");
        // Past k3s's 15-second manifest scan: the skip files keep it from deploying metrics-server again.
        Thread.sleep(20_000);
        assertNotEquals(0, kubectl(cluster, "get", "deployment", "-n", "kube-system", "metrics-server").exitCode(),
                "a deleted metrics-server add-on must stay removed");
    }

    private Cluster waitForActive(String clusterName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
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

    private void waitFor(Cluster cluster, String what, String... args) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 150_000;
        ContainerExec.Result last = null;
        while (System.currentTimeMillis() < deadline) {
            last = kubectl(cluster, args);
            if (last.exitCode() == 0) {
                return;
            }
            Thread.sleep(3000);
        }
        fail("Timed out waiting for " + what + ": " + (last == null ? "" : last.summary()));
    }

    private void waitForAbsent(Cluster cluster, String... resource) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 90_000;
        String[] args = new String[resource.length + 1];
        args[0] = "get";
        System.arraycopy(resource, 0, args, 1, resource.length);
        while (System.currentTimeMillis() < deadline) {
            ContainerExec.Result result = kubectl(cluster, args);
            if (result.exitCode() != 0 && result.summary().contains("NotFound")) {
                return;
            }
            Thread.sleep(3000);
        }
        fail("Still present: " + String.join(" ", resource));
    }

    private ContainerExec.Result kubectl(Cluster cluster, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "kubectl";
        System.arraycopy(args, 0, command, 1, args.length);
        return ContainerExec.run(dockerClient, cluster.getContainerId(), command, 30);
    }
}
