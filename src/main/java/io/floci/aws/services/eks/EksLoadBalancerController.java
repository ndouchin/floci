package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.eks.model.Cluster;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Floci's stand-in for the controllers that give EKS Services of type LoadBalancer their load balancer: the AWS cloud
 * provider and the AWS Load Balancer Controller. Each pass reads the cluster's Services, Nodes and EndpointSlices and,
 * as kubernetes/cloud-provider's service controller does, adds the {@code service.kubernetes.io/load-balancer-cleanup}
 * finalizer first, runs an appliance container forwarding each port to the backend nodes' NodePorts, writes the
 * load balancer's DNS name to {@code status.loadBalancer}, has Floci's DNS answer that name with the appliance's
 * address, and reports the upstream events. A Service that is deleted or stops being a LoadBalancer loses its
 * appliance, its name and then the finalizer. Everything the controller writes derives from the Service, so a
 * restarted Floci picks up where it was without stored state.
 *
 * <p>Backends are the Ready nodes Floci runs as k3s agents that are not labelled
 * {@code node.kubernetes.io/exclude-from-external-load-balancers}; with {@code externalTrafficPolicy: Local}, only
 * those with a ready endpoint of the Service. There are no health checks.
 */
final class EksLoadBalancerController {

    private static final Logger LOG = Logger.getLogger(EksLoadBalancerController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String NORMAL = "Normal";
    static final String WARNING = "Warning";

    /** The cluster's API, through kubectl in the server in the real service. */
    interface Kube {
        record Snapshot(List<JsonNode> services, List<JsonNode> nodes, List<JsonNode> endpointSlices) {}

        Optional<Snapshot> snapshot(Cluster cluster);

        boolean patchService(Cluster cluster, String namespace, String name, String mergePatch);

        boolean patchServiceStatus(Cluster cluster, String namespace, String name, String mergePatch);

        void event(Cluster cluster, EksLoadBalancerSpec spec, String type, String reason, String message);

        /**
         * Has the cluster's CoreDNS answer each load balancer name with its appliance's address: pods resolve through
         * CoreDNS, whose upstream k3s picks, so they would not otherwise find the names Floci makes up.
         */
        void publishNames(Cluster cluster, String region, Map<String, String> addressesByHostname);
    }

    /** The containers standing in for the load balancers. */
    interface Appliances {
        record Spec(String name, String serviceUid, List<EksLoadBalancerSpec.Port> ports, List<String> backends,
                    List<String> sourceRanges) {}

        record Running(String address, Map<String, Integer> hostPorts) {}

        String nameFor(Cluster cluster, String serviceUid);

        Running ensure(Cluster cluster, Spec spec);

        void remove(Cluster cluster, String name);

        Set<String> list(Cluster cluster);
    }

    /** Where Floci's DNS learns the load balancers' names. */
    interface Names {
        void put(Cluster cluster, String hostname, String address);

        void remove(Cluster cluster, String hostname);
    }

    /** What a pass knows of a Service's load balancer between passes. */
    private static final class Record {
        private final String hostname;
        private final String applianceName;
        private final Instant firstSeen;
        private List<String> backends = List.of();
        private String address;
        private boolean ensured;

        Record(String hostname, String applianceName, Instant firstSeen) {
            this.hostname = hostname;
            this.applianceName = applianceName;
            this.firstSeen = firstSeen;
        }
    }

    private final Kube kube;
    private final Appliances appliances;
    private final Names names;
    private final Clock clock;
    private final IntSupplier provisioningDelaySeconds;
    private final Map<String, Map<String, Record>> records = new ConcurrentHashMap<>();
    private final Map<String, String> reportedFailures = new ConcurrentHashMap<>();

    EksLoadBalancerController(Kube kube, Appliances appliances, Names names, Clock clock,
                              IntSupplier provisioningDelaySeconds) {
        this.kube = kube;
        this.appliances = appliances;
        this.names = names;
        this.clock = clock;
        this.provisioningDelaySeconds = provisioningDelaySeconds;
    }

    /**
     * One pass over the cluster.
     *
     * @param backendAddresses the address of each node Floci runs as a k3s agent of the cluster, by node name
     */
    void reconcile(Cluster cluster, String region, Map<String, String> backendAddresses) {
        Optional<Kube.Snapshot> read = kube.snapshot(cluster);
        if (read.isEmpty()) {
            return;
        }
        Kube.Snapshot snapshot = read.get();
        Map<String, String> ready = readyBackends(snapshot.nodes(), backendAddresses);
        Map<String, Record> known = records.computeIfAbsent(key(cluster), ignored -> new ConcurrentHashMap<>());
        Set<String> present = new HashSet<>();
        for (JsonNode service : snapshot.services()) {
            EksLoadBalancerSpec spec = EksLoadBalancerSpec.from(service, cluster.getName(), region);
            present.add(spec.uid());
            try {
                if (!spec.handled() || spec.deleting()) {
                    if (known.containsKey(spec.uid()) || spec.hasCleanupFinalizer()) {
                        cleanUp(cluster, spec, known.remove(spec.uid()));
                    }
                } else {
                    ensure(cluster, spec, known, ready, snapshot.endpointSlices());
                }
            } catch (RuntimeException e) {
                LOG.debugv(e, "Load balancer of Service {0}/{1} in EKS cluster {2} not synced", spec.namespace(),
                        spec.name(), cluster.getName());
                failed(cluster, spec, e.getMessage());
            }
        }
        for (Map.Entry<String, Record> entry : List.copyOf(known.entrySet())) {
            if (!present.contains(entry.getKey())) {
                appliances.remove(cluster, entry.getValue().applianceName);
                names.remove(cluster, entry.getValue().hostname);
                known.remove(entry.getKey());
                reportedFailures.remove(entry.getKey());
            }
        }
        Map<String, String> published = new TreeMap<>();
        known.values().forEach(record -> {
            if (record.address != null) {
                published.put(record.hostname, record.address);
            }
        });
        kube.publishNames(cluster, region, published);
        Set<String> expected = new HashSet<>();
        known.values().forEach(record -> expected.add(record.applianceName));
        for (String appliance : appliances.list(cluster)) {
            if (!expected.contains(appliance)) {
                LOG.infov("Removing load balancer appliance {0} of EKS cluster {1}: no Service has it", appliance,
                        cluster.getName());
                appliances.remove(cluster, appliance);
            }
        }
    }

    /** Forgets a deleted cluster; its appliances go with its containers. */
    void forget(Cluster cluster) {
        Map<String, Record> known = records.remove(key(cluster));
        if (known != null) {
            known.values().forEach(record -> names.remove(cluster, record.hostname));
        }
    }

    private void ensure(Cluster cluster, EksLoadBalancerSpec spec, Map<String, Record> known,
                        Map<String, String> ready, List<JsonNode> endpointSlices) {
        if (!spec.hasCleanupFinalizer() && !patchFinalizers(cluster, spec, true)) {
            return;
        }
        if (spec.error() != null) {
            failed(cluster, spec, spec.error());
            return;
        }
        Record record = known.get(spec.uid());
        if (record == null) {
            record = new Record(spec.hostname(), appliances.nameFor(cluster, spec.uid()), clock.instant());
            known.put(spec.uid(), record);
            kube.event(cluster, spec, NORMAL, "EnsuringLoadBalancer", "Ensuring load balancer");
        }
        List<String> backends = spec.localTrafficPolicy() ? localBackends(spec, ready, endpointSlices)
                : List.copyOf(ready.values());
        List<EksLoadBalancerSpec.Port> forwarded = spec.ports().stream().filter(port -> port.nodePort() > 0).toList();
        Appliances.Running running = appliances.ensure(cluster, new Appliances.Spec(record.applianceName, spec.uid(),
                forwarded, backends, spec.sourceRanges()));
        names.put(cluster, record.hostname, running.address());
        record.address = running.address();
        if (record.ensured && !backends.equals(record.backends)) {
            if (backends.isEmpty()) {
                kube.event(cluster, spec, WARNING, "UnAvailableLoadBalancer", "There are no available nodes for LoadBalancer");
            } else {
                kube.event(cluster, spec, NORMAL, "UpdatedLoadBalancer", "Updated load balancer with new hosts");
            }
        }
        record.backends = backends;
        String annotation = hostPortsAnnotation(running.hostPorts());
        if (!Objects.equals(annotation, spec.hostPortsAnnotation())) {
            kube.patchService(cluster, spec.namespace(), spec.name(), hostPortsPatch(annotation));
        }
        if (clock.instant().isBefore(record.firstSeen.plusSeconds(provisioningDelaySeconds.getAsInt()))) {
            return;
        }
        if (!record.ensured) {
            kube.event(cluster, spec, NORMAL, "EnsuredLoadBalancer", "Ensured load balancer");
        }
        JsonNode desired = JSON.valueToTree(spec.ingress());
        if (!sameIngress(spec.statusIngress(), desired)
                && !kube.patchServiceStatus(cluster, spec.namespace(), spec.name(), statusPatch(desired))) {
            return;
        }
        reportedFailures.remove(spec.uid());
        if (!record.ensured) {
            record.ensured = true;
            LOG.infov("Load balancer of Service {0}/{1} in EKS cluster {2}: {3}", spec.namespace(), spec.name(),
                    cluster.getName(), spec.hostname());
        }
    }

    /** Removes the appliance and the name; the finalizer goes last, so a Service is never gone while its appliance runs. */
    private void cleanUp(Cluster cluster, EksLoadBalancerSpec spec, Record record) {
        kube.event(cluster, spec, NORMAL, "DeletingLoadBalancer", "Deleting load balancer");
        appliances.remove(cluster, record != null ? record.applianceName : appliances.nameFor(cluster, spec.uid()));
        names.remove(cluster, record != null ? record.hostname : spec.hostname());
        if (!spec.deleting()) {
            if (spec.statusIngress().isArray() && !spec.statusIngress().isEmpty()) {
                kube.patchServiceStatus(cluster, spec.namespace(), spec.name(),
                        "{\"status\":{\"loadBalancer\":{\"ingress\":null}}}");
            }
            if (spec.hostPortsAnnotation() != null) {
                kube.patchService(cluster, spec.namespace(), spec.name(), hostPortsPatch(null));
            }
        }
        if (spec.hasCleanupFinalizer() && !patchFinalizers(cluster, spec, false)) {
            return;
        }
        reportedFailures.remove(spec.uid());
        kube.event(cluster, spec, NORMAL, "DeletedLoadBalancer", "Deleted load balancer");
    }

    private void failed(Cluster cluster, EksLoadBalancerSpec spec, String reason) {
        String message = "Error syncing load balancer: failed to ensure load balancer: " + reason;
        if (!message.equals(reportedFailures.put(spec.uid(), message))) {
            LOG.warnv("Service {0}/{1} in EKS cluster {2}: {3}", spec.namespace(), spec.name(), cluster.getName(),
                    message);
            kube.event(cluster, spec, WARNING, "SyncLoadBalancerFailed", message);
        }
    }

    /**
     * Adds or removes the cleanup finalizer, sending the whole list with the Service's {@code resourceVersion}, so a
     * finalizer another controller added since the read is never lost: the API server refuses the patch and the next
     * pass retries.
     */
    private boolean patchFinalizers(Cluster cluster, EksLoadBalancerSpec spec, boolean present) {
        List<String> finalizers = new ArrayList<>(spec.finalizers());
        finalizers.remove(EksLoadBalancerSpec.CLEANUP_FINALIZER);
        if (present) {
            finalizers.add(EksLoadBalancerSpec.CLEANUP_FINALIZER);
        }
        ObjectNode patch = JSON.createObjectNode();
        ObjectNode metadata = patch.putObject("metadata");
        if (spec.resourceVersion() != null) {
            metadata.put("resourceVersion", spec.resourceVersion());
        }
        ArrayNode list = metadata.putArray("finalizers");
        finalizers.forEach(list::add);
        return kube.patchService(cluster, spec.namespace(), spec.name(), write(patch));
    }

    /** {@code TCP/80=127.0.0.1:41234}, one entry per published port, comma-separated, in port order. */
    static String hostPortsAnnotation(Map<String, Integer> hostPorts) {
        if (hostPorts.isEmpty()) {
            return null;
        }
        List<String> entries = new ArrayList<>();
        new TreeMap<>(hostPorts).forEach((key, port) -> entries.add(key + "=127.0.0.1:" + port));
        return String.join(",", entries);
    }

    private static String hostPortsPatch(String annotation) {
        ObjectNode patch = JSON.createObjectNode();
        ObjectNode annotations = patch.putObject("metadata").putObject("annotations");
        if (annotation == null) {
            annotations.putNull(EksLoadBalancerSpec.HOST_PORTS_ANNOTATION);
        } else {
            annotations.put(EksLoadBalancerSpec.HOST_PORTS_ANNOTATION, annotation);
        }
        return write(patch);
    }

    private static String statusPatch(JsonNode ingress) {
        ObjectNode patch = JSON.createObjectNode();
        patch.putObject("status").putObject("loadBalancer").set("ingress", ingress);
        return write(patch);
    }

    static boolean sameIngress(JsonNode current, JsonNode desired) {
        return current.isArray() && current.equals(desired);
    }

    private static Map<String, String> readyBackends(List<JsonNode> nodes, Map<String, String> backendAddresses) {
        Map<String, String> ready = new TreeMap<>();
        for (JsonNode node : nodes) {
            String name = node.path("metadata").path("name").asText();
            if (!backendAddresses.containsKey(name) || node.path("metadata").hasNonNull("deletionTimestamp")
                    || node.path("metadata").path("labels").has(EksNodeAgents.EXCLUDE_FROM_LOAD_BALANCERS)
                    || !isReady(node)) {
                continue;
            }
            ready.put(name, backendAddresses.get(name));
        }
        return ready;
    }

    private static boolean isReady(JsonNode node) {
        for (JsonNode condition : node.path("status").path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                return "True".equals(condition.path("status").asText());
            }
        }
        return false;
    }

    /** {@code externalTrafficPolicy: Local}: kube-proxy answers a NodePort only on nodes with a local ready endpoint. */
    private static List<String> localBackends(EksLoadBalancerSpec spec, Map<String, String> ready,
                                              List<JsonNode> endpointSlices) {
        Map<String, String> local = new TreeMap<>();
        for (JsonNode slice : endpointSlices) {
            if (!spec.namespace().equals(slice.path("metadata").path("namespace").asText())
                    || !spec.name().equals(slice.path("metadata").path("labels")
                            .path("kubernetes.io/service-name").asText())) {
                continue;
            }
            for (JsonNode endpoint : slice.path("endpoints")) {
                String node = endpoint.path("nodeName").asText(null);
                if (endpoint.path("conditions").path("ready").asBoolean(true) && node != null
                        && ready.containsKey(node)) {
                    local.put(node, ready.get(node));
                }
            }
        }
        return List.copyOf(local.values());
    }

    private static String key(Cluster cluster) {
        return cluster.getAccountId() + "/" + cluster.getName();
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write a JSON patch", e);
        }
    }
}
