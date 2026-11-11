package io.floci.aws.services.eks;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.EksAccessEntryService.StoredEntry;
import io.floci.aws.services.eks.model.AssociatedAccessPolicy;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.IamService.EksSessionIdentity;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Maps a verified {@code aws eks get-token} caller to its Kubernetes identity, the way EKS's
 * aws-iam-authenticator does. A cluster that recorded its creator authenticates realistically: the
 * caller's IAM principal (a role session is its role) must be mapped by an access entry in the
 * {@code API} modes, or by the creator rule or the {@code aws-auth} ConfigMap in the
 * {@code CONFIG_MAP} modes, and is otherwise refused. A cluster persisted before the creator was
 * recorded keeps the earlier compatibility identity.
 */
@ApplicationScoped
class EksWorkerAuthentication {

    private static final Logger LOG = Logger.getLogger(EksWorkerAuthentication.class);
    private static final Pattern INSTANCE_ID = Pattern.compile("^i-(\\w{8}|\\w{17})$");
    static final String CREATOR_USERNAME = "kubernetes-admin";

    private final IamService iam;
    private final EksService eks;
    private final Ec2Service ec2;
    private final EksAccessEntryService entries;
    private final EmulatorConfig config;
    private final EksClusterManager clusterManager;
    private final EksAccessPolicyBindings bindings;
    private final Set<String> legacyClustersLogged = ConcurrentHashMap.newKeySet();

    @Inject
    EksWorkerAuthentication(IamService iam, EksService eks, Ec2Service ec2, EksAccessEntryService entries,
                            EmulatorConfig config, EksClusterManager clusterManager,
                            EksAccessPolicyBindings bindings) {
        this.iam = iam;
        this.eks = eks;
        this.ec2 = ec2;
        this.entries = entries;
        this.config = config;
        this.clusterManager = clusterManager;
        this.bindings = bindings;
    }

    EksWorkerAuthentication(IamService iam, EksService eks, Ec2Service ec2, EksAccessEntryService entries,
                            EmulatorConfig config) {
        this(iam, eks, ec2, entries, config, null, null);
    }

    Optional<Map<String, Object>> authenticate(EksTokenValidator.VerifiedToken token, String name,
                                               String account, String region, String createdAt) {
        Optional<EksSessionIdentity> session = iam.findEksSessionIdentity(token.accessKeyId());
        // Expired or revoked temporary sessions never fall through to any other path.
        if (session.isEmpty() && IamService.isTemporaryAccessKey(token.accessKeyId())) {
            return Optional.empty();
        }
        Optional<Cluster> cluster = scopedCluster(name, account, region, createdAt);
        if (cluster.isPresent() && cluster.get().getCreatorArn() != null) {
            return authenticateMapped(cluster.get(), token, session, account);
        }
        cluster.ifPresent(this::logLegacyOnce);
        return authenticateLegacy(token, session, name, account, region, createdAt);
    }

    private Optional<Cluster> scopedCluster(String name, String account, String region, String createdAt) {
        if (eks == null || account == null || region == null || createdAt == null) {
            return Optional.empty();
        }
        return eks.findAuthenticationCluster(account, name)
                .filter(value -> value.getArn() != null && value.getArn().equals(
                        AwsArnUtils.Arn.of("eks", region, account, "cluster/" + name).toString()))
                .filter(value -> createdAt.equals(String.valueOf(value.getCreatedAt())));
    }

    private void logLegacyOnce(Cluster cluster) {
        if (legacyClustersLogged.add(cluster.getArn() + "/" + cluster.getCreatedAt())) {
            LOG.infov("EKS cluster {0} was created before Floci recorded cluster creators, so it keeps the earlier "
                    + "authentication: every IAM user or role of the cluster account is cluster-admin. Recreate the "
                    + "cluster for access entries, aws-auth and creator permissions to apply.", cluster.getName());
        }
    }

    private Optional<Map<String, Object>> authenticateMapped(Cluster cluster, EksTokenValidator.VerifiedToken token,
                                                             Optional<EksSessionIdentity> session, String account) {
        String callerAccount = session.map(EksSessionIdentity::accountId).orElse(token.keyAccountId());
        if (callerAccount == null) {
            return Optional.empty();
        }
        Optional<CallerArns> resolved = RequestScopes.callAs(callerAccount,
                () -> iam.resolveCallerArns(token.accessKeyId()));
        if (resolved.isEmpty() || resolved.get().principalArn() == null
                || !AwsArnUtils.isArnFor(resolved.get().principalArn(), "iam")) {
            return Optional.empty();
        }
        CallerArns caller = resolved.get();
        String principalAccount = AwsArnUtils.accountOrDefault(caller.principalArn(), callerAccount);
        String sessionName = sessionName(caller.callerArn());
        String instanceDns = session.map(EksSessionIdentity::instanceId)
                .flatMap(instanceId -> workerInstanceDns(cluster, account, token, session.get()))
                .orElse(null);

        Optional<Mapping> mapping = mapping(cluster, account, caller);
        if (mapping.isEmpty()) {
            LOG.debugv("EKS cluster {0}: {1} is not mapped to any Kubernetes identity", cluster.getName(),
                    caller.principalArn());
            return Optional.empty();
        }
        Templates templates = new Templates(principalAccount, sessionName, token.accessKeyId(), instanceDns);
        Optional<String> username = templates.render(mapping.get().username() != null
                ? mapping.get().username() : canonicalArn(caller.principalArn()));
        List<String> groups = new ArrayList<>();
        for (String group : mapping.get().groups()) {
            Optional<String> rendered = templates.render(group);
            if (rendered.isEmpty()) {
                return Optional.empty();
            }
            groups.add(rendered.get());
        }
        if (username.isEmpty() || username.get().isBlank()) {
            return Optional.empty();
        }
        String userId = caller.userId() != null ? caller.userId() : principalAccount;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("arn", List.of(caller.callerArn()));
        extra.put("canonicalArn", List.of(canonicalArn(caller.principalArn())));
        extra.put("sessionName", List.of(sessionName));
        extra.put("accessKeyId", List.of(token.accessKeyId()));
        extra.put("principalId", List.of(userId));
        extra.put("sigs.k8s.io/aws-iam-authenticator/principalId", List.of(userId));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username.get());
        user.put("uid", "aws-iam-authenticator:" + principalAccount + ":" + userId);
        user.put("groups", List.copyOf(groups));
        user.put("extra", extra);
        return Optional.of(user);
    }

    private record Mapping(String username, List<String> groups) {}

    /**
     * Who {@code caller} is in the cluster: its access entry first, since access entries take
     * precedence over the ConfigMap for the same principal; then, in a {@code CONFIG_MAP} cluster,
     * the creator's permanent {@code system:masters} mapping; then {@code aws-auth}.
     */
    private Optional<Mapping> mapping(Cluster cluster, String account, CallerArns caller) {
        String mode = cluster.getAccessConfig() == null || cluster.getAccessConfig().authenticationMode() == null
                ? "CONFIG_MAP" : cluster.getAccessConfig().authenticationMode();
        if (!"CONFIG_MAP".equals(mode)) {
            Optional<StoredEntry> entry = entries.entryFor(cluster, account, caller.principalArn(), caller.userId());
            if (entry.isPresent()) {
                return Optional.of(entryMapping(cluster, account, entry.get()));
            }
        }
        if ("API".equals(mode)) {
            return Optional.empty();
        }
        if ("CONFIG_MAP".equals(mode) && caller.principalArn().equals(cluster.getCreatorArn())) {
            return Optional.of(new Mapping(CREATOR_USERNAME, List.of("system:masters")));
        }
        return awsAuth(cluster).flatMap(configMap -> EksAwsAuthConfigMap.find(configMap, canonicalArn(caller.principalArn())))
                .map(found -> new Mapping(found.username(), found.groups()));
    }

    private Mapping entryMapping(Cluster cluster, String account, StoredEntry stored) {
        if ("EC2_LINUX".equals(stored.entry().type())) {
            return new Mapping("system:node:{{EC2PrivateDNSName}}", List.of("system:bootstrappers", "system:nodes"));
        }
        List<String> groups = new ArrayList<>(stored.entry().kubernetesGroups() == null
                ? List.of() : stored.entry().kubernetesGroups());
        groups.add(EksAccessPolicyBindings.group(stored.entry().principalArn()));
        boolean namespaceScoped = stored.accessPolicies().stream().map(AssociatedAccessPolicy::accessScope)
                .anyMatch(scope -> scope != null && "namespace".equals(scope.type()));
        if (namespaceScoped && bindings != null) {
            bindings.refreshSoon(cluster, () -> entries.standardEntries(cluster, account));
        }
        return new Mapping(stored.entry().username(), groups);
    }

    private Optional<String> awsAuth(Cluster cluster) {
        if (clusterManager == null || cluster.getContainerId() == null) {
            return Optional.empty();
        }
        ContainerExec.Result result = clusterManager.execInContainerForResult(cluster.getContainerId(),
                new String[]{"kubectl", "get", "configmap", "aws-auth", "-n", "kube-system", "-o", "json"}, 10);
        return result.exitCode() == 0 ? Optional.ofNullable(result.stdout()) : Optional.empty();
    }

    /** A role's canonical ARN is the role without its path, as aws-iam-authenticator compares it. */
    static String canonicalArn(String principalArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(principalArn);
        if (!arn.resource().startsWith("role/")) {
            return principalArn;
        }
        String roleName = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return AwsArnUtils.Arn.global(arn.partition(), "iam", arn.accountId(), "role/" + roleName).toString();
    }

    private static String sessionName(String callerArn) {
        if (callerArn == null || !AwsArnUtils.isArn(callerArn)) {
            return "";
        }
        String resource = AwsArnUtils.parse(callerArn).resource();
        if (!resource.startsWith("assumed-role/")) {
            return "";
        }
        return resource.substring(resource.lastIndexOf('/') + 1);
    }

    /**
     * The username and group templates aws-iam-authenticator renders. {@code {{EC2PrivateDNSName}}}
     * resolves only for an EC2 instance-profile session whose instance runs with that role's
     * profile; anything else leaves the mapping unusable, so the caller is refused.
     */
    private record Templates(String accountId, String sessionName, String accessKeyId, String instanceDns) {
        Optional<String> render(String template) {
            if (template == null) {
                return Optional.empty();
            }
            String rendered = template;
            if (rendered.contains("{{EC2PrivateDNSName}}")) {
                if (instanceDns == null || !INSTANCE_ID.matcher(sessionName).matches()) {
                    return Optional.empty();
                }
                rendered = rendered.replace("{{EC2PrivateDNSName}}", instanceDns);
            }
            return Optional.of(rendered.replace("{{AccountID}}", accountId)
                    .replace("{{SessionName}}", sessionName.replace("@", "-"))
                    .replace("{{SessionNameRaw}}", sessionName)
                    .replace("{{AccessKeyID}}", accessKeyId));
        }
    }

    private Optional<String> workerInstanceDns(Cluster cluster, String account, EksTokenValidator.VerifiedToken token,
                                               EksSessionIdentity identity) {
        if (!account.equals(identity.accountId()) || identity.roleArn() == null || identity.roleId() == null
                || !AwsArnUtils.regionOrDefault(cluster.getArn(), "").equals(token.region())) {
            return Optional.empty();
        }
        String roleName = identity.roleArn().substring(identity.roleArn().lastIndexOf('/') + 1);
        if (iam.findRole(account, roleName).filter(value -> identity.roleArn().equals(value.getArn())
                && identity.roleId().equals(value.getRoleId())).isEmpty()) {
            return Optional.empty();
        }
        return runningInstance(account, token.region(), identity.instanceId(), roleName)
                .map(Instance::getPrivateDnsName);
    }

    private Optional<Instance> runningInstance(String account, String region, String instanceId, String roleName) {
        if (ec2 == null) {
            return Optional.empty();
        }
        return ec2.findInstanceForAccount(account, region, instanceId)
                .filter(value -> value.getState() != null && "running".equals(value.getState().getName()))
                .filter(value -> region.equals(value.getRegion()))
                .filter(value -> profileMatches(value, account, roleName))
                .filter(value -> value.getPrivateDnsName() != null && !value.getPrivateDnsName().isBlank());
    }

    private Optional<Map<String, Object>> authenticateLegacy(EksTokenValidator.VerifiedToken token,
                                                             Optional<EksSessionIdentity> session, String name,
                                                             String account, String region, String createdAt) {
        if (session.isEmpty()) {
            // The legacy identity is system:masters, so only a key of the cluster's own account gets it.
            return Optional.ofNullable(token.keyAccountId())
                    .filter(owner -> owner.equals(clusterAccount(account)))
                    .flatMap(owner -> legacyIdentity());
        }
        EksSessionIdentity identity = session.get();
        if (identity.instanceId() == null) {
            // Like a long-term key, a session gets the legacy identity only on a cluster of its own account.
            String sessionAccount = identity.roleArn() != null && AwsArnUtils.isArn(identity.roleArn())
                    ? AwsArnUtils.accountOrDefault(identity.roleArn(), identity.accountId()) : identity.accountId();
            return Optional.ofNullable(sessionAccount)
                    .filter(owner -> owner.equals(clusterAccount(account)))
                    .flatMap(owner -> legacyIdentity());
        }
        if (account == null || region == null || createdAt == null || !account.equals(identity.accountId())
                || !region.equals(token.region()) || identity.roleArn() == null || identity.roleId() == null) {
            return Optional.empty();
        }
        Optional<Cluster> cluster = eks.findAuthenticationCluster(account, name)
                .filter(value -> value.getArn() != null && value.getArn().equals(
                        AwsArnUtils.Arn.of("eks", region, account, "cluster/" + name).toString()))
                .filter(value -> createdAt.equals(String.valueOf(value.getCreatedAt())));
        if (cluster.isEmpty()) {
            return Optional.empty();
        }
        String roleName = identity.roleArn().substring(identity.roleArn().lastIndexOf('/') + 1);
        Optional<IamRole> role = iam.findRole(account, roleName)
                .filter(value -> identity.roleArn().equals(value.getArn()) && identity.roleId().equals(value.getRoleId()));
        if (role.isEmpty()) {
            return Optional.empty();
        }
        try {
            if (entries.workerEntry(cluster.get(), account, identity.roleArn(), identity.roleId()).isEmpty()) {
                return Optional.empty();
            }
        } catch (AwsException rejected) {
            return Optional.empty();
        }
        Optional<Instance> instance = runningInstance(account, region, identity.instanceId(), roleName);
        if (instance.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Map.of("username", "system:node:" + instance.get().getPrivateDnsName(),
                "uid", account + ":" + identity.roleId() + ":" + identity.instanceId(),
                "groups", List.of("system:bootstrappers", "system:nodes")));
    }

    private boolean profileMatches(Instance instance, String account, String roleName) {
        String arn = instance.getIamInstanceProfileArn();
        if (!AwsArnUtils.isArnFor(arn, "iam")) {
            return false;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!account.equals(parsed.accountId()) || !parsed.resource().startsWith("instance-profile/")) {
            return false;
        }
        return iam.findInstanceProfile(account, arn.substring(arn.lastIndexOf('/') + 1))
                .filter(profile -> arn.equals(profile.getArn()))
                .filter(profile -> List.of(roleName).equals(profile.getRoleNames())).isPresent();
    }

    /** Unscoped webhook paths predate per-account clusters, so they serve the default account. */
    private String clusterAccount(String account) {
        return account != null ? account : config.defaultAccountId();
    }

    private static Optional<Map<String, Object>> legacyIdentity() {
        return Optional.of(Map.of("username", "floci:aws-iam", "uid", "floci-aws-iam",
                "groups", List.of("system:masters")));
    }
}
