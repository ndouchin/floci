package io.floci.aws.services.eks;

import io.floci.aws.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Sends {@code aws eks get-token} tokens to the token webhook of metadata-only clusters and checks
 * who a CONFIG_MAP cluster maps: the IAM principal that created it, and nobody else.
 */
@QuarkusTest
class EksClusterCreatorAuthenticationIntegrationTest {

    private static final String ACCOUNT = "864213579024";

    @Inject
    EksService eks;

    @Test
    void configMapClusterMapsItsCreatorOnly() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userName = "creator-" + suffix;
        given().header("Authorization", auth(ACCOUNT, "iam"))
                .formParam("Action", "CreateUser").formParam("UserName", userName)
                .post("/").then().statusCode(200);
        XmlPath key = given().header("Authorization", auth(ACCOUNT, "iam"))
                .formParam("Action", "CreateAccessKey").formParam("UserName", userName)
                .post("/").then().statusCode(200).extract().xmlPath();
        String userKey = key.getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        String userSecret = key.getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey");
        XmlPath rootSession = given().header("Authorization", auth(ACCOUNT, "sts"))
                .formParam("Action", "GetSessionToken").formParam("Version", "2011-06-15")
                .post("/").then().statusCode(200).extract().xmlPath();
        String credentials = "GetSessionTokenResponse.GetSessionTokenResult.Credentials.";
        String rootKey = rootSession.getString(credentials + "AccessKeyId");
        String rootSecret = rootSession.getString(credentials + "SecretAccessKey");
        String rootToken = rootSession.getString(credentials + "SessionToken");

        String byRoot = "by-root-" + suffix;
        String byUser = "by-user-" + suffix;
        createCluster(ACCOUNT, byRoot).body("cluster", not(hasKey("creatorArn")));
        createCluster(userKey, byUser);
        try {
            review(byRoot, rootKey, rootSecret, rootToken)
                    .body("status.authenticated", equalTo(true))
                    .body("status.user.username", equalTo("kubernetes-admin"))
                    .body("status.user.groups", contains("system:masters"))
                    .body("status.user.uid", equalTo("aws-iam-authenticator:" + ACCOUNT + ":" + ACCOUNT));
            review(byRoot, userKey, userSecret, null).body("status.authenticated", equalTo(false));

            review(byUser, userKey, userSecret, null)
                    .body("status.authenticated", equalTo(true))
                    .body("status.user.username", equalTo("kubernetes-admin"))
                    .body("status.user.groups", contains("system:masters"))
                    .body("status.user.extra.arn", contains("arn:aws:iam::" + ACCOUNT + ":user/" + userName));
            review(byUser, rootKey, rootSecret, rootToken).body("status.authenticated", equalTo(false));
        } finally {
            given().header("Authorization", auth(ACCOUNT, "eks")).delete("/clusters/" + byRoot);
            given().header("Authorization", auth(ACCOUNT, "eks")).delete("/clusters/" + byUser);
            given().header("Authorization", auth(ACCOUNT, "iam")).formParam("Action", "DeleteAccessKey")
                    .formParam("UserName", userName).formParam("AccessKeyId", userKey).post("/");
            given().header("Authorization", auth(ACCOUNT, "iam")).formParam("Action", "DeleteUser")
                    .formParam("UserName", userName).post("/");
        }
    }

    private static ValidatableResponse createCluster(String accessKeyId, String name) {
        return given().header("Authorization", auth(accessKeyId, "eks")).contentType("application/json")
                .body(Map.of("name", name, "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/cluster"))
                .post("/clusters").then().statusCode(200)
                .body("cluster.accessConfig.authenticationMode", equalTo("CONFIG_MAP"));
    }

    private ValidatableResponse review(String clusterName, String accessKeyId, String secret, String sessionToken)
            throws Exception {
        String token = SigV4TokenTestHelper.createEksToken(clusterName, accessKeyId, secret, Instant.now(), 60,
                sessionToken);
        String createdAt = eks.findAuthenticationCluster(ACCOUNT, clusterName).orElseThrow().getCreatedAt().toString();
        return given().contentType("application/json")
                .body(Map.of("apiVersion", "authentication.k8s.io/v1", "kind", "TokenReview",
                        "spec", Map.of("token", token)))
                .post("/_floci/eks/clusters/" + clusterName + "/token-webhook/scope/" + ACCOUNT + "/us-east-1/"
                        + createdAt)
                .then().statusCode(200);
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                + "/20261111/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
