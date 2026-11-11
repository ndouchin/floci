package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * UpdateClusterVersion, UpdateNodegroupConfig, UpdateNodegroupVersion, DescribeUpdate and ListUpdates
 * against metadata-only clusters, whose updates finish as soon as they are recorded.
 */
@QuarkusTest
class EksUpdatesIntegrationTest {

    private static final String JSON = "application/json";

    @Test
    void clusterVersionMovesOneMinorAtATimeAndWaitsForItsNodegroups() {
        String cluster = createCluster("1.30");
        String nodegroup = createNodegroup(cluster);
        try {
            for (String version : List.of("1.30", "1.32", "1.29")) {
                given().contentType(JSON).body(Map.of("version", version))
                        .post("/clusters/" + cluster + "/updates").then().statusCode(400)
                        .body("__type", equalTo("InvalidParameterException"))
                        .body("message", equalTo("Unsupported Kubernetes minor version update from 1.30 to " + version));
            }
            given().contentType(JSON).body(Map.of("version", "1.x"))
                    .post("/clusters/" + cluster + "/updates").then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));

            String updateId = given().contentType(JSON).body(Map.of("version", "1.31", "clientRequestToken", "upgrade-1"))
                    .post("/clusters/" + cluster + "/updates").then().statusCode(200)
                    .body("update.status", equalTo("InProgress"))
                    .body("update.type", equalTo("VersionUpdate"))
                    .body("update.params.type", contains("Version", "PlatformVersion"))
                    .body("update.params.value", contains("1.31", "eks.1"))
                    .body("update.errors", hasSize(0))
                    .body("update.createdAt", notNullValue())
                    .extract().path("update.id");
            given().contentType(JSON).body(Map.of("version", "1.31", "clientRequestToken", "upgrade-1"))
                    .post("/clusters/" + cluster + "/updates").then().statusCode(200)
                    .body("update.id", equalTo(updateId));
            given().get("/clusters/" + cluster + "/updates/" + updateId).then().statusCode(200)
                    .body("update.status", equalTo("Successful"));
            given().get("/clusters/" + cluster).then().statusCode(200)
                    .body("cluster.status", equalTo("ACTIVE"))
                    .body("cluster.version", equalTo("1.31"))
                    .body("cluster.platformVersion", equalTo("eks.1"));

            given().contentType(JSON).body(Map.of("version", "1.32"))
                    .post("/clusters/" + cluster + "/updates").then().statusCode(400)
                    .body("__type", equalTo("InvalidRequestException"))
                    .body("message", equalTo("Nodegroups " + nodegroup
                            + " must be updated to match cluster version 1.31 before updating cluster version"));

            given().contentType(JSON).body(Map.of("version", "1.32"))
                    .post("/clusters/" + cluster + "/node-groups/" + nodegroup + "/update-version").then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
            String nodegroupUpdate = given().contentType(JSON).body(Map.of())
                    .post("/clusters/" + cluster + "/node-groups/" + nodegroup + "/update-version").then().statusCode(200)
                    .body("update.type", equalTo("VersionUpdate"))
                    .body("update.params.type", contains("Version", "ReleaseVersion"))
                    .body("update.params.value", contains("1.31", EksNodeLayout.releaseVersion("1.31")))
                    .extract().path("update.id");
            given().queryParam("nodegroupName", nodegroup)
                    .get("/clusters/" + cluster + "/updates/" + nodegroupUpdate).then().statusCode(200)
                    .body("update.status", equalTo("Successful"));
            given().get("/clusters/" + cluster + "/node-groups/" + nodegroup).then().statusCode(200)
                    .body("nodegroup.status", equalTo("ACTIVE"))
                    .body("nodegroup.version", equalTo("1.31"))
                    .body("nodegroup.releaseVersion", equalTo(EksNodeLayout.releaseVersion("1.31")));
            given().contentType(JSON).body(Map.of("version", "1.30"))
                    .post("/clusters/" + cluster + "/node-groups/" + nodegroup + "/update-version").then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));

            String second = given().contentType(JSON).body(Map.of("version", "1.32"))
                    .post("/clusters/" + cluster + "/updates").then().statusCode(200)
                    .extract().path("update.id");
            given().get("/clusters/" + cluster + "/updates").then().statusCode(200)
                    .body("updateIds", contains(updateId, second))
                    .body("$", not(hasKey("nextToken")));
            String token = given().queryParam("maxResults", 1).get("/clusters/" + cluster + "/updates")
                    .then().statusCode(200).body("updateIds", contains(updateId))
                    .extract().path("nextToken");
            given().queryParam("maxResults", 1).queryParam("nextToken", token).get("/clusters/" + cluster + "/updates")
                    .then().statusCode(200).body("updateIds", contains(second));
            given().queryParam("nodegroupName", nodegroup).get("/clusters/" + cluster + "/updates")
                    .then().statusCode(200).body("updateIds", contains(nodegroupUpdate));
            given().queryParam("nodegroupName", "missing").get("/clusters/" + cluster + "/updates")
                    .then().statusCode(404);
            given().queryParam("nodegroupName", "other")
                    .get("/clusters/" + cluster + "/updates/" + nodegroupUpdate).then().statusCode(404);
        } finally {
            given().delete("/clusters/" + cluster + "/node-groups/" + nodegroup);
            given().delete("/clusters/" + cluster);
        }
    }

    @Test
    void nodegroupConfigUpdateChangesLabelsTaintsAndScaling() {
        String cluster = createCluster("1.31");
        String nodegroup = createNodegroup(cluster);
        try {
            String updateId = given().contentType(JSON).body("""
                    {"labels": {"addOrUpdateLabels": {"tier": "backend", "team": "core"}, "removeLabels": ["env"]},
                     "taints": {"addOrUpdateTaints": [{"key": "gpu", "value": "true", "effect": "NO_SCHEDULE"}],
                                "removeTaints": [{"key": "dedicated", "effect": "NO_EXECUTE"}]},
                     "scalingConfig": {"desiredSize": 3, "maxSize": 4},
                     "updateConfig": {"maxUnavailable": 2}}
                    """)
                    .post("/clusters/" + cluster + "/node-groups/" + nodegroup + "/update-config").then().statusCode(200)
                    .body("update.type", equalTo("ConfigUpdate"))
                    .body("update.status", equalTo("InProgress"))
                    .body("update.params.type", contains("LabelsToAdd", "LabelsToRemove", "TaintsToAdd",
                            "TaintsToRemove", "MaxSize", "DesiredSize", "MaxUnavailable"))
                    .body("update.params.find { it.type == 'LabelsToAdd' }.value",
                            equalTo("{\"team\":\"core\",\"tier\":\"backend\"}"))
                    .body("update.params.find { it.type == 'DesiredSize' }.value", equalTo("3"))
                    .extract().path("update.id");
            given().queryParam("nodegroupName", nodegroup)
                    .get("/clusters/" + cluster + "/updates/" + updateId).then().statusCode(200)
                    .body("update.status", equalTo("Successful"));
            given().get("/clusters/" + cluster + "/node-groups/" + nodegroup).then().statusCode(200)
                    .body("nodegroup.status", equalTo("ACTIVE"))
                    .body("nodegroup.labels", equalTo(Map.of("tier", "backend", "team", "core")))
                    .body("nodegroup.taints.key", containsInAnyOrder("spot", "gpu"))
                    .body("nodegroup.scalingConfig.minSize", equalTo(1))
                    .body("nodegroup.scalingConfig.desiredSize", equalTo(3))
                    .body("nodegroup.scalingConfig.maxSize", equalTo(4))
                    .body("nodegroup.updateConfig.maxUnavailable", equalTo(2));
            given().get("/clusters/" + cluster + "/updates").then().statusCode(200)
                    .body("updateIds", hasSize(0));
            given().queryParam("nodegroupName", nodegroup).get("/clusters/" + cluster + "/updates")
                    .then().statusCode(200).body("updateIds", hasItems(updateId));

            given().contentType(JSON).body("""
                    {"labels": {"addOrUpdateLabels": {"tier": "web"}, "removeLabels": ["tier"]}}
                    """)
                    .post("/clusters/" + cluster + "/node-groups/" + nodegroup + "/update-config").then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
            given().contentType(JSON).body("""
                    {"scalingConfig": {"minSize": 5}}
                    """)
                    .post("/clusters/" + cluster + "/node-groups/" + nodegroup + "/update-config").then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
            given().contentType(JSON).body("""
                    {"taints": {"addOrUpdateTaints": [{"key": "x", "effect": "NoSchedule"}]}}
                    """)
                    .post("/clusters/" + cluster + "/node-groups/" + nodegroup + "/update-config").then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
            given().contentType(JSON).body("{}")
                    .post("/clusters/" + cluster + "/node-groups/" + nodegroup + "/update-config").then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
            given().contentType(JSON).body("{\"scalingConfig\": {\"desiredSize\": 1}}")
                    .post("/clusters/" + cluster + "/node-groups/missing/update-config").then().statusCode(404);
        } finally {
            given().delete("/clusters/" + cluster + "/node-groups/" + nodegroup);
            given().delete("/clusters/" + cluster);
        }
    }

    private static String createCluster(String version) {
        String name = "updates-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType(JSON)
                .body(Map.of("name", name, "version", version, "roleArn", "arn:aws:iam::000000000000:role/eks"))
                .post("/clusters").then().statusCode(200);
        return name;
    }

    private static String createNodegroup(String cluster) {
        String name = "ng-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType(JSON).body("""
                {"nodegroupName": "%s", "subnets": ["subnet-12345678"],
                 "nodeRole": "arn:aws:iam::000000000000:role/eks-node",
                 "labels": {"env": "dev"},
                 "taints": [{"key": "dedicated", "value": "batch", "effect": "NO_EXECUTE"},
                            {"key": "spot", "effect": "PREFER_NO_SCHEDULE"}],
                 "scalingConfig": {"minSize": 1, "desiredSize": 1, "maxSize": 2}}
                """.formatted(name))
                .post("/clusters/" + cluster + "/node-groups").then().statusCode(200);
        return name;
    }
}
