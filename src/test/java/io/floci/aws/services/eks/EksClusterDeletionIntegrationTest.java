package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** DeleteCluster refuses a cluster that still has managed node groups or Fargate profiles, as EKS does. */
@QuarkusTest
class EksClusterDeletionIntegrationTest {

    private static final String JSON = "application/json";

    @Test
    void attachedNodegroupsAndFargateProfilesKeepTheClusterUntilTheyAreDeleted() {
        String cluster = "deletion-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType(JSON)
                .body(Map.of("name", cluster, "roleArn", "arn:aws:iam::000000000000:role/eks"))
                .post("/clusters").then().statusCode(200);
        given().contentType(JSON).body(Map.of("nodegroupName", "workers", "subnets", new String[]{"subnet-12345678"},
                        "nodeRole", "arn:aws:iam::000000000000:role/eks-node"))
                .post("/clusters/" + cluster + "/node-groups").then().statusCode(200);
        given().contentType(JSON).body(Map.of("fargateProfileName", "serverless",
                        "podExecutionRoleArn", "arn:aws:iam::000000000000:role/eks-fargate",
                        "selectors", new Object[]{Map.of("namespace", "default")}))
                .post("/clusters/" + cluster + "/fargate-profiles").then().statusCode(200);

        given().delete("/clusters/" + cluster).then().statusCode(409)
                .body("__type", equalTo("ResourceInUseException"))
                .body("message", equalTo("Cluster has nodegroups attached"));
        given().get("/clusters/" + cluster).then().statusCode(200).body("cluster.status", equalTo("ACTIVE"));

        given().delete("/clusters/" + cluster + "/node-groups/workers").then().statusCode(200);
        given().delete("/clusters/" + cluster).then().statusCode(409)
                .body("__type", equalTo("ResourceInUseException"))
                .body("message", equalTo("Cluster has Fargate Profiles in ACTIVE/CREATING state"));

        given().delete("/clusters/" + cluster + "/fargate-profiles/serverless").then().statusCode(200);
        given().delete("/clusters/" + cluster).then().statusCode(200).body("cluster.status", equalTo("DELETING"));
        given().get("/clusters/" + cluster).then().statusCode(404);
    }
}
