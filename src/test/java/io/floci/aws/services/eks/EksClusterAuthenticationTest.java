package io.floci.aws.services.eks;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.eks.model.AccessConfig;
import io.floci.aws.services.eks.model.AccessScope;
import io.floci.aws.services.eks.model.AssociateAccessPolicyRequest;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.eks.model.CreateAccessEntryRequest;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.IamService.EksSessionIdentity;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.IamUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EksClusterAuthenticationTest {
    private static final String ACCOUNT = "123456789012";
    private static final String OTHER_ACCOUNT = "111122223333";
    private static final String REGION = "us-east-1";
    private static final Instant CREATED = Instant.parse("2026-11-10T00:00:00Z");
    private static final String CREATOR = "arn:aws:iam::" + ACCOUNT + ":user/creator";
    private static final String DEVELOPER = "arn:aws:iam::" + ACCOUNT + ":user/developer";
    private static final String DEPLOY_ROLE = "arn:aws:iam::" + ACCOUNT + ":role/ci/deploy";
    private static final String VIEW = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy";

    private final IamService iam = mock(IamService.class);
    private final EksService eks = mock(EksService.class);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final EksClusterManager clusterManager = mock(EksClusterManager.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EksAccessEntryService entries = new EksAccessEntryService(new InMemoryStorage<>(), iam);
    private final EksWorkerAuthentication auth =
            new EksWorkerAuthentication(iam, eks, ec2, entries, config, clusterManager, null);
    private final Cluster cluster = new Cluster();

    @BeforeEach
    void setup() {
        when(config.defaultAccountId()).thenReturn("000000000000");
        cluster.setName("demo");
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/demo");
        cluster.setAccountId(ACCOUNT);
        cluster.setCreatedAt(CREATED);
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setContainerId("k3s");
        cluster.setCreatorArn(CREATOR);
        cluster.setAccessConfig(new AccessConfig("CONFIG_MAP", true));
        when(eks.findAuthenticationCluster(ACCOUNT, "demo")).thenReturn(Optional.of(cluster));
        when(iam.findUser(ACCOUNT, "creator")).thenReturn(Optional.of(new IamUser("AIDACREATOR", "creator", "/", CREATOR)));
        when(iam.findUser(ACCOUNT, "developer")).thenReturn(Optional.of(new IamUser("AIDADEV", "developer", "/", DEVELOPER)));
        when(iam.findRole(ACCOUNT, "deploy")).thenReturn(Optional.of(new IamRole("AROADEPLOY", "deploy", "/ci/", DEPLOY_ROLE, "{}")));
        user("AKIACREATOR", CREATOR, "AIDACREATOR");
        user("AKIADEV", DEVELOPER, "AIDADEV");
        when(iam.findEksSessionIdentity("ASIADEPLOY")).thenReturn(Optional.of(
                new EksSessionIdentity(ACCOUNT, DEPLOY_ROLE, null, null)));
        when(iam.resolveCallerArns("ASIADEPLOY")).thenReturn(Optional.of(new CallerArns(
                "arn:aws:sts::" + ACCOUNT + ":assumed-role/deploy/pipeline@ci", DEPLOY_ROLE,
                CallerArns.TYPE_ASSUMED_ROLE, "AROADEPLOY:pipeline@ci", null)));
        noAwsAuth();
    }

    @Test
    void configMapCreatorIsClusterAdminAndNobodyElseIsMapped() {
        Map<String, Object> creator = authenticate("AKIACREATOR").orElseThrow();

        assertEquals(EksWorkerAuthentication.CREATOR_USERNAME, creator.get("username"));
        assertEquals(List.of("system:masters"), creator.get("groups"));
        assertEquals("aws-iam-authenticator:" + ACCOUNT + ":AIDACREATOR", creator.get("uid"));
        assertTrue(authenticate("AKIADEV").isEmpty());
        assertTrue(authenticate("ASIADEPLOY").isEmpty());
    }

    @Test
    void awsAuthMapsRolesByTheirPathlessArnAndRendersSessionTemplates() {
        awsAuth("""
                - rolearn: arn:aws:iam::123456789012:role/deploy
                  username: deployer:{{SessionName}}
                  groups: [ci-deployers, "team:{{AccountID}}"]
                """, """
                - userarn: arn:aws:iam::123456789012:user/developer
                  username: dev
                  groups: [system:masters]
                """);

        Map<String, Object> role = authenticate("ASIADEPLOY").orElseThrow();
        Map<String, Object> user = authenticate("AKIADEV").orElseThrow();

        assertEquals("deployer:pipeline-ci", role.get("username"));
        assertEquals(List.of("ci-deployers", "team:" + ACCOUNT), role.get("groups"));
        assertEquals("aws-iam-authenticator:" + ACCOUNT + ":AROADEPLOY:pipeline@ci", role.get("uid"));
        Map<?, ?> extra = (Map<?, ?>) role.get("extra");
        assertEquals(List.of("arn:aws:iam::" + ACCOUNT + ":role/deploy"), extra.get("canonicalArn"));
        assertEquals(List.of("pipeline@ci"), extra.get("sessionName"));
        assertEquals("dev", user.get("username"));
    }

    @Test
    void apiModeMapsOnlyAccessEntriesAndAddsTheEntryGroup() {
        cluster.setAccessConfig(new AccessConfig("API", true));
        entries.bootstrapCreatorEntry(cluster, CREATOR, "AIDACREATOR");
        entries.create(cluster, new CreateAccessEntryRequest(DEPLOY_ROLE, "STANDARD", null, List.of("deployers"),
                null, null));
        entries.associate(cluster, DEPLOY_ROLE, new AssociateAccessPolicyRequest(VIEW, new AccessScope("cluster", null)));
        awsAuth("""
                - userarn: arn:aws:iam::123456789012:user/developer
                  username: dev
                  groups: [system:masters]
                """, "");

        Map<String, Object> creator = authenticate("AKIACREATOR").orElseThrow();
        Map<String, Object> role = authenticate("ASIADEPLOY").orElseThrow();

        assertEquals(CREATOR, creator.get("username"));
        assertEquals(List.of(EksAccessPolicyBindings.group(CREATOR)), creator.get("groups"));
        assertEquals("arn:aws:sts::" + ACCOUNT + ":assumed-role/deploy/pipeline-ci", role.get("username"));
        assertEquals(List.of("deployers", EksAccessPolicyBindings.group(DEPLOY_ROLE)), role.get("groups"));
        assertTrue(authenticate("AKIADEV").isEmpty(), "API mode ignores aws-auth");
    }

    @Test
    void deletingTheCreatorEntryRevokesTheCreatorInApiModes() {
        cluster.setAccessConfig(new AccessConfig("API_AND_CONFIG_MAP", true));
        entries.bootstrapCreatorEntry(cluster, CREATOR, "AIDACREATOR");
        assertTrue(authenticate("AKIACREATOR").isPresent());

        entries.delete(cluster, CREATOR);

        assertTrue(authenticate("AKIACREATOR").isEmpty());
    }

    @Test
    void accessEntryTakesPrecedenceOverAwsAuthInApiAndConfigMap() {
        cluster.setAccessConfig(new AccessConfig("API_AND_CONFIG_MAP", false));
        entries.create(cluster, new CreateAccessEntryRequest(DEVELOPER, "STANDARD", "from-entry", List.of(), null, null));
        awsAuth("", """
                - userarn: arn:aws:iam::123456789012:user/developer
                  username: from-configmap
                """);

        assertEquals("from-entry", authenticate("AKIADEV").orElseThrow().get("username"));
        assertTrue(authenticate("AKIACREATOR").isEmpty(), "no bootstrap entry, and the creator rule is CONFIG_MAP's");
    }

    @Test
    void recreatedPrincipalNoLongerMatchesItsEntry() {
        cluster.setAccessConfig(new AccessConfig("API", false));
        entries.create(cluster, new CreateAccessEntryRequest(DEVELOPER, "STANDARD", null, null, null, null));
        user("AKIADEV", DEVELOPER, "AIDARECREATED");

        assertTrue(authenticate("AKIADEV").isEmpty());
    }

    @Test
    void legacyClusterKeepsAdminForItsOwnAccountButNotForOtherAccountsSessions() {
        cluster.setCreatorArn(null);
        when(iam.findEksSessionIdentity("ASIAOTHER")).thenReturn(Optional.of(
                new EksSessionIdentity(OTHER_ACCOUNT, "arn:aws:iam::" + OTHER_ACCOUNT + ":role/x", null, null)));

        assertEquals(List.of("system:masters"), authenticate("AKIADEV").orElseThrow().get("groups"));
        assertEquals(List.of("system:masters"), authenticate("ASIADEPLOY").orElseThrow().get("groups"));
        assertTrue(authenticate("ASIAOTHER").isEmpty());
    }

    @Test
    void canonicalArnDropsTheRolePathOnly() {
        assertEquals("arn:aws:iam::123456789012:role/deploy", EksWorkerAuthentication.canonicalArn(DEPLOY_ROLE));
        assertEquals(DEVELOPER, EksWorkerAuthentication.canonicalArn(DEVELOPER));
    }

    private Optional<Map<String, Object>> authenticate(String accessKeyId) {
        String keyAccount = accessKeyId.startsWith("AKIA") ? ACCOUNT : null;
        return auth.authenticate(new EksTokenValidator.VerifiedToken(accessKeyId, REGION, keyAccount),
                "demo", ACCOUNT, REGION, CREATED.toString());
    }

    private void user(String accessKeyId, String arn, String userId) {
        when(iam.findEksSessionIdentity(accessKeyId)).thenReturn(Optional.empty());
        when(iam.resolveCallerArns(accessKeyId)).thenReturn(Optional.of(new CallerArns(arn, arn,
                CallerArns.TYPE_USER, userId, arn.substring(arn.lastIndexOf('/') + 1))));
    }

    private void noAwsAuth() {
        when(clusterManager.execInContainerForResult(eq("k3s"), any(String[].class), anyInt()))
                .thenReturn(new ContainerExec.Result(1, "Error from server (NotFound)", "", false));
    }

    private void awsAuth(String mapRoles, String mapUsers) {
        String json = "{\"data\":{\"mapRoles\":" + quote(mapRoles) + ",\"mapUsers\":" + quote(mapUsers) + "}}";
        when(clusterManager.execInContainerForResult(eq("k3s"), any(String[].class), anyInt()))
                .thenReturn(new ContainerExec.Result(0, json, "", false));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
