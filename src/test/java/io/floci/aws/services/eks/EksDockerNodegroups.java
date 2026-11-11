package io.floci.aws.services.eks;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Node groups for the Docker tests of clusters that run their nodes as k3s agents: a cluster has no node until a
 * node group gives it one, and EKS refuses to delete a cluster that still has node groups.
 */
final class EksDockerNodegroups {

    private EksDockerNodegroups() {}

    /** Creates a one-node node group and waits until its node is Ready. */
    static void createOneNode(String clusterName, String nodegroupName) {
        given().contentType("application/json")
                .body("{\"nodegroupName\":\"" + nodegroupName + "\",\"subnets\":[\"subnet-12345678\"],"
                        + "\"nodeRole\":\"arn:aws:iam::000000000000:role/eks-node\"}")
                .when().post("/clusters/" + clusterName + "/node-groups")
                .then().statusCode(200);
        awaitActive(clusterName, nodegroupName);
    }

    static void awaitActive(String clusterName, String nodegroupName) {
        long deadline = System.currentTimeMillis() + 300_000;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = given().when().get("/clusters/" + clusterName + "/node-groups/" + nodegroupName)
                    .then().statusCode(200).extract().path("nodegroup.status");
            if ("ACTIVE".equals(status)) {
                return;
            }
            assertNotEquals("CREATE_FAILED", status, "node group " + nodegroupName + " failed");
            pause();
        }
        fail("Node group " + nodegroupName + " is still " + status);
    }

    /** Deletes the cluster's node groups, waits until they are gone, then deletes the cluster. */
    static void teardown(EksService eksService, String clusterName) {
        if (eksService.listClusters().stream().noneMatch(clusterName::equals)) {
            return;
        }
        for (String nodegroup : eksService.listNodeGroups(clusterName)) {
            eksService.deleteNodeGroup(clusterName, nodegroup);
        }
        long deadline = System.currentTimeMillis() + 180_000;
        while (!eksService.listNodeGroups(clusterName).isEmpty() && System.currentTimeMillis() < deadline) {
            pause();
        }
        eksService.deleteCluster(clusterName);
    }

    private static void pause() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an EKS node group", e);
        }
    }
}
