package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.EpochSeconds;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eks.model.AccessEntry;
import io.floci.aws.services.eks.model.AccessScope;
import io.floci.aws.services.eks.model.AssociateAccessPolicyRequest;
import io.floci.aws.services.eks.model.AssociatedAccessPolicy;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.eks.model.CreateAccessEntryRequest;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.IamUser;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Access-entry and access-policy management metadata; Kubernetes authorization is handled separately. */
@ApplicationScoped
public class EksAccessEntryService {
    static final String CLUSTER_ADMIN_POLICY = "AmazonEKSClusterAdminPolicy";

    private final StorageBackend<String, StoredEntry> entries;
    private final IamService iam;
    private final Clock clock;
    private final EksAccessPolicyBindings bindings;

    @Inject
    public EksAccessEntryService(StorageFactory factory, IamService iam, EksAccessPolicyBindings bindings) {
        this(factory.create("eks", "eks-access-entries.json", new TypeReference<Map<String, StoredEntry>>() {}), iam,
                Clock.systemUTC(), bindings);
    }

    EksAccessEntryService(StorageBackend<String, StoredEntry> entries, IamService iam) {
        this(entries, iam, Clock.systemUTC());
    }

    EksAccessEntryService(StorageBackend<String, StoredEntry> entries, IamService iam, Clock clock) {
        this(entries, iam, clock, null);
    }

    EksAccessEntryService(StorageBackend<String, StoredEntry> entries, IamService iam, Clock clock,
                          EksAccessPolicyBindings bindings) {
        this.entries = entries;
        this.iam = iam;
        this.clock = clock;
        this.bindings = bindings;
    }

    @RegisterForReflection
    public record StoredEntry(AccessEntry entry, String principalId, String clientRequestToken,
                              List<AssociatedAccessPolicy> accessPolicies) {
        public StoredEntry {
            accessPolicies = accessPolicies == null ? List.of() : List.copyOf(accessPolicies);
        }
    }

    @RegisterForReflection
    public record Page(List<String> accessEntries, String nextToken) {}

    @RegisterForReflection
    public record PolicyPage(List<AssociatedAccessPolicy> associatedAccessPolicies, String nextToken) {}

    public AssociatedAccessPolicy associate(Cluster cluster, String principal, AssociateAccessPolicyRequest request) {
        AssociatedAccessPolicy association = storeAssociation(cluster, principal, request);
        reconcileBindings(cluster);
        return association;
    }

    private synchronized AssociatedAccessPolicy storeAssociation(Cluster cluster, String principal,
                                                                 AssociateAccessPolicyRequest request) {
        StoredEntry stored = storedEntry(cluster, principal);
        if (request == null || request.policyArn() == null) {
            throw invalid("policyArn is required");
        }
        requireAccessPolicyArn(cluster, request.policyArn());
        AccessScope scope = accessScope(request.accessScope());
        if (!"STANDARD".equals(stored.entry().type())) {
            throw new AwsException("InvalidRequestException",
                    "Access policies can only be associated with STANDARD access entries", 400);
        }
        double now = EpochSeconds.fromMillis(clock.millis());
        double associatedAt = stored.accessPolicies().stream()
                .filter(existing -> existing.policyArn().equals(request.policyArn()))
                .mapToDouble(AssociatedAccessPolicy::associatedAt).findFirst().orElse(now);
        AssociatedAccessPolicy association = new AssociatedAccessPolicy(request.policyArn(), scope, associatedAt, now);
        List<AssociatedAccessPolicy> policies = new ArrayList<>(stored.accessPolicies());
        policies.removeIf(existing -> existing.policyArn().equals(request.policyArn()));
        policies.add(association);
        policies.sort(Comparator.comparing(AssociatedAccessPolicy::policyArn));
        entries.put(prefix(cluster) + principal, new StoredEntry(stored.entry(), stored.principalId(),
                stored.clientRequestToken(), policies));
        return association;
    }

    public synchronized PolicyPage listAccessPolicies(Cluster cluster, String principal, Integer maxResults,
                                                      String nextToken) {
        List<AssociatedAccessPolicy> policies = storedEntry(cluster, principal).accessPolicies();
        // The newline ends the principal, so a token for role/a:b is not read as one for role/a.
        PaginatedResult<AssociatedAccessPolicy> page = Pagination.paginate(policies, AssociatedAccessPolicy::policyArn,
                pageSize(maxResults), nextToken, "policies:" + prefix(cluster) + principal + "\n",
                token -> invalid("Invalid nextToken"));
        return new PolicyPage(page.items(), page.nextToken());
    }

    public void disassociate(Cluster cluster, String principal, String policyArn) {
        if (removeAssociation(cluster, principal, policyArn)) {
            reconcileBindings(cluster);
        }
    }

    private synchronized boolean removeAssociation(Cluster cluster, String principal, String policyArn) {
        StoredEntry stored = storedEntry(cluster, principal);
        requireAccessPolicyArn(cluster, policyArn);
        List<AssociatedAccessPolicy> policies = stored.accessPolicies().stream()
                .filter(existing -> !existing.policyArn().equals(policyArn)).toList();
        if (policies.size() == stored.accessPolicies().size()) {
            return false; // EKS documents no error for a policy the entry does not hold
        }
        entries.put(prefix(cluster) + principal, new StoredEntry(stored.entry(), stored.principalId(),
                stored.clientRequestToken(), policies));
        return true;
    }

    private StoredEntry storedEntry(Cluster cluster, String principal) {
        requireApiAccess(cluster);
        return entries.get(prefix(cluster) + principal)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Access entry not found", 404));
    }

    private static AccessScope accessScope(AccessScope scope) {
        if (scope == null || scope.type() == null) {
            throw invalid("accessScope.type is required");
        }
        List<String> namespaces = scope.namespaces() == null ? List.of() : scope.namespaces();
        if ("cluster".equals(scope.type())) {
            if (!namespaces.isEmpty()) {
                throw invalid("namespaces can only be specified for a namespace access scope");
            }
            return new AccessScope("cluster", List.of());
        }
        if (!"namespace".equals(scope.type())) {
            throw invalid("accessScope.type must be cluster or namespace");
        }
        if (namespaces.isEmpty() || namespaces.stream().anyMatch(namespace -> namespace == null || namespace.isBlank())) {
            throw invalid("A namespace access scope requires nonempty namespace names");
        }
        // Terraform models namespaces as a set, so a repeated name must not come back twice.
        return new AccessScope("namespace", List.copyOf(new LinkedHashSet<>(namespaces)));
    }

    public synchronized AccessEntry create(Cluster cluster, CreateAccessEntryRequest request) {
        requireApiAccess(cluster);
        if (request == null || request.principalArn() == null) {
            throw invalid("principalArn is required");
        }
        String type = request.type() == null ? "STANDARD" : request.type();
        if (!"STANDARD".equals(type) && !"EC2_LINUX".equals(type)) {
            throw invalid("Floci currently supports STANDARD and EC2_LINUX access entries");
        }
        boolean node = "EC2_LINUX".equals(type);
        if (node && (request.username() != null || request.kubernetesGroups() != null)) {
            throw invalid("EC2_LINUX entries cannot specify username or kubernetesGroups");
        }
        String principal = request.principalArn();
        AwsArnUtils.Arn arn = AwsArnUtils.isArn(principal) ? AwsArnUtils.parse(principal) : null;
        AwsArnUtils.Arn clusterArn = AwsArnUtils.parse(cluster.getArn());
        if (arn == null || !arn.partition().equals(clusterArn.partition())
                || !"iam".equals(arn.service()) || !arn.region().isEmpty() || !arn.accountId().matches("[0-9]{12}")
                || !(arn.resource().startsWith("role/") || (!node && arn.resource().startsWith("user/")))
                || arn.resource().startsWith("role/aws-service-role/")
                || principal.endsWith("/") || (node && !arn.accountId().equals(clusterArn.accountId()))) {
            throw invalid("principalArn must identify an IAM principal; EC2_LINUX requires a role in the cluster account");
        }
        String name = principal.substring(principal.lastIndexOf('/') + 1);
        boolean role = arn.resource().startsWith("role/");
        String principalId = role
                ? iam.findRole(arn.accountId(), name).filter(value -> principal.equals(value.getArn()))
                        .map(IamRole::getRoleId).orElseThrow(() -> invalid("IAM role does not exist"))
                : iam.findUser(arn.accountId(), name).filter(value -> principal.equals(value.getArn()))
                        .map(IamUser::getUserId).orElseThrow(() -> invalid("IAM user does not exist"));
        String username = node ? "system:node:{{EC2PrivateDNSName}}" : request.username();
        if (username != null && !node && (username.isBlank()
                || username.matches("^(system|eks|aws|amazon|iam):.*"))) {
            throw invalid("Invalid username");
        }
        if (username != null && !node) {
            for (String placeholder : List.of("{{SessionName}}", "{{SessionNameRaw}}")) {
                int position = username.indexOf(placeholder);
                if (position >= 0 && !username.substring(0, position).contains(":")) {
                    throw invalid("A session-name placeholder must be preceded by a colon");
                }
            }
        }
        if (username == null) {
            username = role ? AwsArnUtils.Arn.global(arn.partition(), "sts", arn.accountId(),
                    "assumed-role/" + name + "/{{SessionName}}").toString() : principal;
        }
        List<String> groups = node ? List.of("system:nodes")
                : request.kubernetesGroups() == null ? List.of() : request.kubernetesGroups();
        if (groups.stream().anyMatch(group -> group == null || group.isBlank())) {
            throw invalid("kubernetesGroups must contain nonempty strings");
        }
        Map<String, String> tags = request.tags() == null ? Map.of() : request.tags();
        if (tags.size() > 50 || tags.entrySet().stream().anyMatch(tag -> tag.getKey() == null
                || tag.getKey().isEmpty() || tag.getKey().length() > 128 || tag.getValue() == null
                || tag.getValue().length() > 256)) {
            throw invalid("Invalid access entry tags");
        }
        String prefix = prefix(cluster);
        String token = request.clientRequestToken();
        if (token != null) {
            for (StoredEntry stored : entries.scan(key -> key.startsWith(prefix))) {
                if (token.equals(stored.clientRequestToken())) {
                    AccessEntry previous = stored.entry();
                    if (principal.equals(previous.principalArn()) && type.equals(previous.type())
                            && username.equals(previous.username()) && groups.equals(previous.kubernetesGroups())
                            && tags.equals(previous.tags())) {
                        return previous;
                    }
                    throw invalid("clientRequestToken was already used with different parameters");
                }
            }
        }
        String key = prefix + principal;
        if (entries.get(key).isPresent()) {
            throw new AwsException("ResourceInUseException", "Access entry already exists", 409);
        }
        double now = EpochSeconds.fromMillis(clock.millis());
        String entryArn = new AwsArnUtils.Arn(clusterArn.partition(), "eks", clusterArn.region(), clusterArn.accountId(),
                "access-entry/" + cluster.getName() + "/" + (role ? "role" : "user") + "/" + arn.accountId() + "/" + name
                        + "/" + UUID.randomUUID()).toString();
        AccessEntry entry = new AccessEntry(entryArn, cluster.getName(), principal,
                type, username, List.copyOf(groups), Map.copyOf(tags), now, now);
        entries.put(key, new StoredEntry(entry, principalId, token, List.of()));
        return entry;
    }

    synchronized Optional<AccessEntry> workerEntry(Cluster cluster, String accountId, String principal, String roleId) {
        requireApiAccess(cluster);
        String key = prefix(cluster) + principal;
        Optional<StoredEntry> stored = entries instanceof AccountAwareStorageBackend<StoredEntry> aware
                ? aware.getForAccount(accountId, key) : entries.get(key);
        return stored.filter(value -> roleId != null && roleId.equals(value.principalId()))
                .map(StoredEntry::entry).filter(entry -> "EC2_LINUX".equals(entry.type()));
    }

    public synchronized AccessEntry describe(Cluster cluster, String principal) {
        return storedEntry(cluster, principal).entry();
    }

    public void delete(Cluster cluster, String principal) {
        if (!removeEntry(cluster, principal).accessPolicies().isEmpty()) {
            reconcileBindings(cluster);
        }
    }

    private synchronized StoredEntry removeEntry(Cluster cluster, String principal) {
        StoredEntry stored = storedEntry(cluster, principal);
        entries.delete(prefix(cluster) + principal);
        return stored;
    }

    /**
     * Records the access entry EKS gives the IAM principal that created the cluster when
     * {@code bootstrapClusterCreatorAdminPermissions} is true in an {@code API} or
     * {@code API_AND_CONFIG_MAP} cluster: a STANDARD entry with {@value #CLUSTER_ADMIN_POLICY} at
     * cluster scope. It is an ordinary entry, so deleting it or disassociating the policy revokes the
     * creator's access, as in EKS. Written while the cluster is still CREATING; its RBAC binding is
     * applied once the API server answers.
     */
    public synchronized void bootstrapCreatorEntry(Cluster cluster, String principalArn, String callerUserId) {
        AwsArnUtils.Arn clusterArn = AwsArnUtils.parse(cluster.getArn());
        AwsArnUtils.Arn principal = AwsArnUtils.parse(principalArn);
        String resource = principal.resource();
        boolean role = resource.startsWith("role/");
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        String principalId = role
                ? iam.findRole(principal.accountId(), name).filter(value -> principalArn.equals(value.getArn()))
                        .map(IamRole::getRoleId).orElse(null)
                : resource.startsWith("user/")
                ? iam.findUser(principal.accountId(), name).filter(value -> principalArn.equals(value.getArn()))
                        .map(IamUser::getUserId).orElse(null)
                : principal.accountId();
        if (principalId == null && callerUserId != null) {
            principalId = callerUserId.contains(":") ? callerUserId.substring(0, callerUserId.indexOf(':')) : callerUserId;
        }
        String username = role ? AwsArnUtils.Arn.global(principal.partition(), "sts", principal.accountId(),
                "assumed-role/" + name + "/{{SessionName}}").toString() : principalArn;
        double now = EpochSeconds.fromMillis(clock.millis());
        String entryArn = new AwsArnUtils.Arn(clusterArn.partition(), "eks", clusterArn.region(), clusterArn.accountId(),
                "access-entry/" + cluster.getName() + "/" + (role ? "role" : "user") + "/" + principal.accountId() + "/"
                        + name + "/" + UUID.randomUUID()).toString();
        AccessEntry entry = new AccessEntry(entryArn, cluster.getName(), principalArn, "STANDARD", username,
                List.of(), Map.of(), now, now);
        String policyArn = new AwsArnUtils.Arn(clusterArn.partition(), "eks", "",
                "aws", "cluster-access-policy/" + CLUSTER_ADMIN_POLICY).toString(); // partition-literal: the AWS-managed account, "aws" in every partition
        AssociatedAccessPolicy policy = new AssociatedAccessPolicy(policyArn,
                new AccessScope("cluster", List.of()), now, now);
        entries.put(prefix(cluster) + principalArn, new StoredEntry(entry, principalId, null, List.of(policy)));
    }

    /**
     * The access entry a verified IAM principal authenticates through: the entry for exactly that
     * principal ARN (a role session is its role, path included), still bound to the principal ID it
     * was created for, so a principal deleted and recreated under the same name no longer matches.
     * {@code callerUserId} is the caller's aws:userid, whose part before a colon is a role's ID.
     */
    synchronized Optional<StoredEntry> entryFor(Cluster cluster, String accountId, String principalArn,
                                                String callerUserId) {
        String key = prefix(cluster) + principalArn;
        Optional<StoredEntry> stored = entries instanceof AccountAwareStorageBackend<StoredEntry> aware
                ? aware.getForAccount(accountId, key) : entries.get(key);
        String callerPrincipalId = callerUserId == null ? null
                : callerUserId.contains(":") ? callerUserId.substring(0, callerUserId.indexOf(':')) : callerUserId;
        return stored.filter(value -> value.principalId() != null && value.principalId().equals(callerPrincipalId));
    }

    /** Every STANDARD entry of the cluster in the account that owns it, for the RBAC its policies grant. */
    synchronized List<StoredEntry> standardEntries(Cluster cluster, String accountId) {
        String prefix = prefix(cluster);
        List<StoredEntry> stored = entries instanceof AccountAwareStorageBackend<StoredEntry> aware
                ? aware.scanAllAccountEntries(key -> key.startsWith(prefix)).stream()
                        .filter(entry -> accountId == null || accountId.equals(entry.accountId()))
                        .map(AccountAwareStorageBackend.AccountEntry::value).toList()
                : entries.scan(key -> key.startsWith(prefix));
        return stored.stream().filter(value -> "STANDARD".equals(value.entry().type())).toList();
    }

    /** Applies the cluster's access policies as Kubernetes RBAC, now that its API server answers. */
    public void reconcileBindings(Cluster cluster) {
        if (bindings != null) {
            bindings.reconcile(cluster, standardEntries(cluster, cluster.getAccountId()));
        }
    }

    public synchronized Page list(Cluster cluster, Integer maxResults, String nextToken) {
        return list(cluster, maxResults, nextToken, null);
    }

    public synchronized Page list(Cluster cluster, Integer maxResults, String nextToken, String associatedPolicyArn) {
        requireApiAccess(cluster);
        String prefix = prefix(cluster);
        List<String> principals = entries.scan(key -> key.startsWith(prefix)).stream()
                .filter(stored -> associatedPolicyArn == null || stored.accessPolicies().stream()
                        .anyMatch(policy -> policy.policyArn().equals(associatedPolicyArn)))
                .map(stored -> stored.entry().principalArn()).toList();
        PaginatedResult<String> page = Pagination.paginate(principals, principal -> principal, pageSize(maxResults),
                nextToken, "entries:" + prefix, token -> invalid("Invalid nextToken"));
        return new Page(page.items(), page.nextToken());
    }

    public synchronized void deleteClusterEntries(Cluster cluster) {
        String prefix = prefix(cluster);
        for (StoredEntry stored : entries.scan(key -> key.startsWith(prefix))) {
            entries.delete(prefix + stored.entry().principalArn());
        }
    }

    /**
     * Applies {@code change} to the tags of the access entry {@code accessEntryArn} names on
     * {@code cluster} and returns them, or empty when the cluster has no entry with that ARN. Entries
     * are stored by principal, which the ARN does not carry whole, so the cluster's entries are
     * searched. This is how TagResource, UntagResource and ListTagsForResource reach an entry.
     */
    public synchronized Optional<Map<String, String>> retag(Cluster cluster, String accessEntryArn,
                                                            UnaryOperator<Map<String, String>> change) {
        String prefix = prefix(cluster);
        for (String key : entries.keys()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            Optional<StoredEntry> found = entries.get(key)
                    .filter(stored -> accessEntryArn.equals(stored.entry().accessEntryArn()));
            if (found.isPresent()) {
                StoredEntry stored = found.get();
                AccessEntry current = stored.entry();
                Map<String, String> tags = Map.copyOf(change.apply(current.tags() == null ? Map.of() : current.tags()));
                if (!tags.equals(current.tags())) {
                    AccessEntry retagged = new AccessEntry(current.accessEntryArn(), current.clusterName(),
                            current.principalArn(), current.type(), current.username(), current.kubernetesGroups(),
                            tags, current.createdAt(), current.modifiedAt());
                    entries.put(key, new StoredEntry(retagged, stored.principalId(), stored.clientRequestToken(),
                            stored.accessPolicies()));
                }
                return Optional.of(tags);
            }
        }
        return Optional.empty();
    }

    private static String prefix(Cluster cluster) {
        // A recreated cluster must not inherit access entries or pagination tokens from its predecessor.
        return cluster.getArn() + "/" + Objects.toString(cluster.getCreatedAt()) + "/";
    }

    private static void requireApiAccess(Cluster cluster) {
        if (cluster.getAccessConfig() == null || "CONFIG_MAP".equals(cluster.getAccessConfig().authenticationMode())) {
            throw new AwsException("InvalidRequestException", "Cluster authentication mode must be API or API_AND_CONFIG_MAP", 400);
        }
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400);
        }
    }

    static int pageSize(Integer maxResults) {
        int limit = maxResults == null ? 100 : maxResults;
        if (limit < 1 || limit > 100) {
            throw invalid("maxResults must be between 1 and 100");
        }
        return limit;
    }

    private static void requireAccessPolicyArn(Cluster cluster, String policyArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(policyArn);
        } catch (IllegalArgumentException e) {
            throw invalid("policyArn must identify an EKS cluster access policy");
        }
        boolean accessPolicy = arn.partition().equals(AwsArnUtils.parse(cluster.getArn()).partition())
                && arn.service().equals("eks")
                && arn.region().isEmpty()
                && arn.accountId().equals("aws") // partition-literal: the AWS-managed account, "aws" in every partition
                && arn.resource().matches("cluster-access-policy/[A-Za-z0-9]+");
        if (!accessPolicy) {
            throw invalid("policyArn must identify an EKS cluster access policy");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
