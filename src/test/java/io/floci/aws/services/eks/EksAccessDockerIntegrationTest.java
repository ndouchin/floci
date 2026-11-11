package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Authenticates real {@code aws eks get-token} tokens against a k3s cluster's API server, which
 * hands each one to Floci's token webhook, and checks what each IAM principal is then allowed to do:
 * the creator through its bootstrap access entry, a user through an access entry and a
 * namespace-scoped access policy, an assumed role through {@code aws-auth}, and nobody else.
 */
@QuarkusTest
@TestProfile(EksAccessDockerIntegrationTest.Profile.class)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class EksAccessDockerIntegrationTest {

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // The k3s API server calls the webhook on Floci's configured port, so the test listener uses it.
            return Map.of(
                    "floci.services.eks.mock", "false",
                    "quarkus.http.test-port", "4597",
                    "floci.port", "4597",
                    "floci.base-url", "http://localhost:4597");
        }
    }

    private static final String JSON = "application/json";
    private static final String ACCOUNT = "000000000000";
    private static final String VIEW = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    DockerClient dockerClient;

    @Inject
    EksService eksService;

    @Inject
    IamService iamService;

    private final HttpClient kube = trustingClient();

    @BeforeEach
    void requireDocker() {
        boolean available;
        try {
            dockerClient.pingCmd().exec();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for EKS access tests");
    }

    @Test
    void iamPrincipalsGetExactlyTheAccessTheirMappingGrants() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "access-" + suffix;
        AccessKey creator = user("eks-creator-" + suffix);
        AccessKey developer = user("eks-dev-" + suffix);
        String roleName = "eks-deployer-" + suffix;
        iamService.createRole(roleName, "/ci/", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:aws:iam::" + ACCOUNT + ":root\"},\"Action\":\"sts:AssumeRole\"}]}",
                null, 3600, null);
        cleanup.register(() -> iamService.deleteRole(roleName));

        given().contentType(JSON).header("Authorization", authorization(creator.getAccessKeyId(), "eks"))
                .body("""
                        {"name": "%s", "roleArn": "arn:aws:iam::%s:role/eks",
                         "accessConfig": {"authenticationMode": "API_AND_CONFIG_MAP"}}
                        """.formatted(clusterName, ACCOUNT))
                .when().post("/clusters")
                .then().statusCode(200);
        cleanup.register(() -> eksService.deleteCluster(clusterName));
        Cluster cluster = waitForActive(clusterName);

        expect(cluster, creator, null, "GET", "/api/v1/namespaces/kube-system/pods", null, status -> status == 200);
        expect(cluster, developer, null, "GET", "/api/v1/namespaces/default/pods", null, status -> status == 401);

        String developerArn = "arn:aws:iam::" + ACCOUNT + ":user/eks-dev-" + suffix;
        given().contentType(JSON)
                .body("{\"principalArn\":\"" + developerArn + "\"}")
                .when().post("/clusters/" + clusterName + "/access-entries")
                .then().statusCode(200);
        given().contentType(JSON).urlEncodingEnabled(false)
                .body("{\"policyArn\":\"" + VIEW + "\",\"accessScope\":{\"type\":\"namespace\",\"namespaces\":[\"default\"]}}")
                .when().post("/clusters/" + clusterName + "/access-entries/"
                        + developerArn.replace(":", "%3A").replace("/", "%2F") + "/access-policies")
                .then().statusCode(200);

        expect(cluster, developer, null, "GET", "/api/v1/namespaces/default/pods", null, status -> status == 200);
        expect(cluster, developer, null, "GET", "/api/v1/namespaces/kube-system/pods", null, status -> status == 403);
        expect(cluster, developer, null, "POST", "/api/v1/namespaces/default/configmaps",
                "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"denied\"}}", status -> status == 403);

        String awsAuth = """
                {"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"aws-auth","namespace":"kube-system"},
                 "data":{"mapRoles":"- rolearn: arn:aws:iam::%s:role/%s\\n  username: deployer:{{SessionName}}\\n  groups:\\n  - system:masters\\n"}}
                """.formatted(ACCOUNT, roleName);
        expect(cluster, creator, null, "POST", "/api/v1/namespaces/kube-system/configmaps", awsAuth,
                status -> status == 201);
        XmlPath assumed = given().header("Authorization", authorization(creator.getAccessKeyId(), "sts"))
                .formParam("Action", "AssumeRole").formParam("Version", "2011-06-15")
                .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT + ":role/ci/" + roleName)
                .formParam("RoleSessionName", "pipeline")
                .when().post("/")
                .then().statusCode(200).extract().xmlPath();
        AccessKey session = new AccessKey(
                assumed.getString("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId"),
                assumed.getString("AssumeRoleResponse.AssumeRoleResult.Credentials.SecretAccessKey"), null);
        String sessionToken = assumed.getString("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken");

        expect(cluster, session, sessionToken, "GET", "/api/v1/nodes", null, status -> status == 200);
        expect(cluster, session, null, "GET", "/api/v1/nodes", null, status -> status == 401);
    }

    private AccessKey user(String name) {
        iamService.createUser(name, "/");
        AccessKey key = iamService.createAccessKey(name);
        cleanup.register(() -> {
            iamService.deleteAccessKey(name, key.getAccessKeyId());
            iamService.deleteUser(name);
        });
        return key;
    }

    private static String authorization(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261110/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=0000";
    }

    private Cluster waitForActive(String clusterName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            Cluster cluster = eksService.describeCluster(clusterName);
            if (cluster.getStatus() == ClusterStatus.ACTIVE && cluster.getContainerId() != null) {
                return cluster;
            }
            Thread.sleep(2000);
        }
        fail("Cluster " + clusterName + " did not become ACTIVE");
        return null;
    }

    /**
     * Sends a request with a fresh token until the API server answers as expected, within a minute: the
     * webhook result for a token is cached by the API server, and RBAC is reconciled in the background.
     */
    private void expect(Cluster cluster, AccessKey key, String sessionToken, String method, String path,
                        String body, IntPredicate expected) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        int status = -1;
        String response = "";
        while (System.currentTimeMillis() < deadline) {
            String token = SigV4TokenTestHelper.createEksToken(cluster.getName(), key.getAccessKeyId(),
                    key.getSecretAccessKey(), Instant.now(), 60, sessionToken);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(cluster.getEndpoint() + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", JSON);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> answer = kube.send(request.build(), HttpResponse.BodyHandlers.ofString());
            status = answer.statusCode();
            response = answer.body();
            if (expected.test(status)) {
                return;
            }
            Thread.sleep(2000);
        }
        ContainerExec.Result bindings = ContainerExec.run(dockerClient, cluster.getContainerId(),
                new String[]{"kubectl", "get", "clusterrolebindings,rolebindings", "-A", "-l",
                        EksAccessPolicyBindings.MANAGED_LABEL}, 15);
        fail(method + " " + path + " answered " + status + ": " + response
                + "\nFloci bindings:\n" + bindings.stdout());
    }

    private static HttpClient trustingClient() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // The k3s API server's certificate is self-signed for the test.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // The k3s API server's certificate is self-signed for the test.
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("");
            return HttpClient.newBuilder().sslContext(context).sslParameters(parameters)
                    .connectTimeout(Duration.ofSeconds(10)).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
