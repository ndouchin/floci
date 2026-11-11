package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.EksAccessEntryService.StoredEntry;
import io.floci.aws.services.eks.model.AssociatedAccessPolicy;
import io.floci.aws.services.eks.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Enforces EKS access policies in a k3s cluster as Kubernetes RBAC. EKS evaluates an access
 * entry's policies in its own authorizer; Floci gives each STANDARD entry a group of its own, which
 * the token webhook adds to the entry's identity, and binds that group to the ClusterRole each policy
 * matches: a ClusterRoleBinding for a cluster scope, a RoleBinding in each listed namespace for a
 * namespace scope. Every binding is labelled as Floci's and reconciled as a whole, so a removed entry
 * or policy takes its bindings with it.
 */
@ApplicationScoped
public class EksAccessPolicyBindings {

    private static final Logger LOG = Logger.getLogger(EksAccessPolicyBindings.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String GROUP_PREFIX = "floci:eks-access-entry:";
    static final String MANAGED_LABEL = "eks.floci.io/access-policy";
    static final String PRINCIPAL_ANNOTATION = "eks.floci.io/principal-arn";
    static final String POLICY_ANNOTATION = "eks.floci.io/policy-arn";
    private static final long REFRESH_INTERVAL_MILLIS = 15_000;

    /**
     * The policies whose permissions match a Kubernetes user-facing ClusterRole, per the EKS Best
     * Practices Guide's identity and access management table.
     */
    static final Map<String, String> POLICY_CLUSTER_ROLES = Map.of(
            "AmazonEKSClusterAdminPolicy", "cluster-admin",
            "AmazonEKSAdminPolicy", "admin",
            "AmazonEKSEditPolicy", "edit",
            "AmazonEKSViewPolicy", "view");

    private final EksClusterManager clusterManager;
    private final Map<String, Long> lastRefresh = new ConcurrentHashMap<>();
    private final Set<String> unenforcedPoliciesLogged = ConcurrentHashMap.newKeySet();

    @Inject
    public EksAccessPolicyBindings(EksClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    /** The group a STANDARD access entry's identity carries so its policies' bindings can name it. */
    static String group(String principalArn) {
        return GROUP_PREFIX + principalArn;
    }

    /**
     * Applies the bindings {@code entries} call for and deletes Floci's bindings they no longer call
     * for. A namespace scope covers the namespaces that exist now; a name ending in {@code *} covers
     * every existing namespace it prefixes. A no-op without a cluster container, as in mock mode.
     */
    public void reconcile(Cluster cluster, List<StoredEntry> entries) {
        String containerId = cluster == null ? null : cluster.getContainerId();
        if (containerId == null) {
            return;
        }
        synchronized (this) {
            try {
                ContainerExec.Result namespaces = clusterManager.execInContainerForResult(containerId,
                        new String[]{"kubectl", "get", "namespaces", "-o", "jsonpath={.items[*].metadata.name}"}, 15);
                if (namespaces.exitCode() != 0) {
                    throw new IllegalStateException(namespaces.stdout());
                }
                Set<String> existing = new LinkedHashSet<>(List.of(namespaces.stdout().trim().split("\\s+")));
                List<ObjectNode> desired = desiredBindings(entries, existing);
                logUnenforcedPolicies(cluster, entries);
                deleteStale(containerId, desired);
                if (!desired.isEmpty()) {
                    ObjectNode list = JSON.createObjectNode();
                    list.put("apiVersion", "v1");
                    list.put("kind", "List");
                    list.putArray("items").addAll(desired);
                    String manifest = list.toString().replace("'", "'\\''");
                    ContainerExec.Result applied = clusterManager.execInContainerForResult(containerId,
                            new String[]{"sh", "-c", "echo '" + manifest + "' | kubectl apply -f -"}, 30);
                    if (applied.exitCode() != 0) {
                        throw new IllegalStateException(applied.stdout());
                    }
                }
                lastRefresh.put(clusterKey(cluster), System.currentTimeMillis());
                LOG.infov("Applied {0} access policy binding(s) in EKS cluster {1}", desired.size(), cluster.getName());
            } catch (Exception e) {
                LOG.warnv(e, "Could not apply the access policies of EKS cluster {0} as RBAC: {1}",
                        cluster.getName(), e.getMessage());
            }
        }
    }

    /**
     * Reconciles in the background when the last reconcile is older than fifteen seconds, so a
     * namespace created after a namespace-scoped policy was associated gets its RoleBinding when a
     * principal holding that policy next authenticates.
     */
    void refreshSoon(Cluster cluster, Supplier<List<StoredEntry>> entries) {
        long now = System.currentTimeMillis();
        Long last = lastRefresh.get(clusterKey(cluster));
        if (last != null && now - last < REFRESH_INTERVAL_MILLIS) {
            return;
        }
        lastRefresh.put(clusterKey(cluster), now);
        CompletableFuture.runAsync(() -> reconcile(cluster, entries.get()));
    }

    static List<ObjectNode> desiredBindings(List<StoredEntry> entries, Set<String> namespaces) {
        Map<String, ObjectNode> bindings = new LinkedHashMap<>();
        for (StoredEntry stored : entries) {
            String principal = stored.entry().principalArn();
            for (AssociatedAccessPolicy policy : stored.accessPolicies()) {
                String clusterRole = POLICY_CLUSTER_ROLES.get(policyName(policy.policyArn()));
                if (clusterRole == null) {
                    continue;
                }
                String name = bindingName(principal, policy.policyArn());
                if (policy.accessScope() == null || "cluster".equals(policy.accessScope().type())) {
                    bindings.put("/" + name, binding("ClusterRoleBinding", name, null, clusterRole, principal,
                            policy.policyArn()));
                    continue;
                }
                for (String namespace : scopedNamespaces(policy.accessScope().namespaces(), namespaces)) {
                    bindings.put(namespace + "/" + name, binding("RoleBinding", name, namespace, clusterRole,
                            principal, policy.policyArn()));
                }
            }
        }
        return new ArrayList<>(bindings.values());
    }

    static Set<String> scopedNamespaces(List<String> requested, Set<String> existing) {
        Set<String> matched = new LinkedHashSet<>();
        if (requested == null) {
            return matched;
        }
        for (String namespace : requested) {
            if (namespace.endsWith("*")) {
                String prefix = namespace.substring(0, namespace.length() - 1);
                existing.stream().filter(candidate -> candidate.startsWith(prefix)).forEach(matched::add);
            } else if (existing.contains(namespace)) {
                matched.add(namespace);
            }
        }
        return matched;
    }

    static String policyName(String policyArn) {
        if (policyArn == null || !AwsArnUtils.isArn(policyArn)) {
            return "";
        }
        String resource = AwsArnUtils.parse(policyArn).resource();
        return resource.substring(resource.lastIndexOf('/') + 1);
    }

    static String bindingName(String principalArn, String policyArn) {
        return "floci-eks-" + policyName(policyArn).toLowerCase(Locale.ROOT).replace("amazoneks", "")
                .replace("policy", "") + "-" + Digests.sha256Hex(principalArn).substring(0, 16);
    }

    private static ObjectNode binding(String kind, String name, String namespace, String clusterRole,
                                      String principal, String policyArn) {
        ObjectNode binding = JSON.createObjectNode();
        binding.put("apiVersion", "rbac.authorization.k8s.io/v1");
        binding.put("kind", kind);
        ObjectNode metadata = binding.putObject("metadata");
        metadata.put("name", name);
        if (namespace != null) {
            metadata.put("namespace", namespace);
        }
        metadata.putObject("labels").put(MANAGED_LABEL, "true").put("app.kubernetes.io/managed-by", "floci");
        metadata.putObject("annotations").put(PRINCIPAL_ANNOTATION, principal).put(POLICY_ANNOTATION, policyArn);
        ObjectNode roleRef = binding.putObject("roleRef");
        roleRef.put("apiGroup", "rbac.authorization.k8s.io");
        roleRef.put("kind", "ClusterRole");
        roleRef.put("name", clusterRole);
        ObjectNode subject = binding.putArray("subjects").addObject();
        subject.put("apiGroup", "rbac.authorization.k8s.io");
        subject.put("kind", "Group");
        subject.put("name", group(principal));
        return binding;
    }

    private void deleteStale(String containerId, List<ObjectNode> desired) throws Exception {
        ContainerExec.Result current = clusterManager.execInContainerForResult(containerId,
                new String[]{"kubectl", "get", "clusterrolebindings,rolebindings", "-A", "-l", MANAGED_LABEL,
                        "-o", "json"}, 15);
        if (current.exitCode() != 0) {
            throw new IllegalStateException(current.stdout());
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (ObjectNode binding : desired) {
            wanted.add(binding.path("kind").asText() + "/" + binding.path("metadata").path("namespace").asText("")
                    + "/" + binding.path("metadata").path("name").asText());
        }
        JsonNode items = JSON.readTree(current.stdout()).path("items");
        if (!(items instanceof ArrayNode array)) {
            return;
        }
        for (JsonNode item : array) {
            String kind = item.path("kind").asText();
            String namespace = item.path("metadata").path("namespace").asText("");
            String name = item.path("metadata").path("name").asText();
            if (wanted.contains(kind + "/" + namespace + "/" + name)) {
                continue;
            }
            List<String> command = new ArrayList<>(List.of("kubectl", "delete",
                    kind.toLowerCase(Locale.ROOT), name, "--ignore-not-found"));
            if (!namespace.isEmpty()) {
                command.addAll(List.of("-n", namespace));
            }
            clusterManager.execInContainerForResult(containerId, command.toArray(String[]::new), 15);
        }
    }

    private void logUnenforcedPolicies(Cluster cluster, List<StoredEntry> entries) {
        for (StoredEntry stored : entries) {
            for (AssociatedAccessPolicy policy : stored.accessPolicies()) {
                String name = policyName(policy.policyArn());
                if (!POLICY_CLUSTER_ROLES.containsKey(name)
                        && unenforcedPoliciesLogged.add(clusterKey(cluster) + "/" + name)) {
                    LOG.infov("EKS cluster {0}: access policy {1} has no Kubernetes RBAC equivalent in Floci and "
                            + "grants nothing; only AmazonEKSClusterAdminPolicy, AmazonEKSAdminPolicy, "
                            + "AmazonEKSEditPolicy and AmazonEKSViewPolicy are enforced", cluster.getName(), name);
                }
            }
        }
    }

    private static String clusterKey(Cluster cluster) {
        return cluster.getArn() + "/" + cluster.getCreatedAt();
    }
}
