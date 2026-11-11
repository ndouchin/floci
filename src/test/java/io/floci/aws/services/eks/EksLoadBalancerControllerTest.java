package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.eks.model.Cluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller's rules against a fake API server and fake appliances: the finalizer before anything else, the
 * EKS-shaped hostname in the status, the upstream events, foreign classes left alone, an invalid Service left
 * pending, and cleanup on delete and on type change.
 * https://github.com/kubernetes/cloud-provider/blob/6d61da6ed70a044418fb1de39b8d440c015b683c/controllers/service/controller.go#L863-L962
 */
class EksLoadBalancerControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGION = "us-east-1";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
    private FakeKube kube;
    private FakeAppliances appliances;
    private final Map<String, String> names = new HashMap<>();
    private int delaySeconds;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        kube = new FakeKube();
        appliances = new FakeAppliances();
        cluster = new Cluster();
        cluster.setName("demo");
        cluster.setAccountId("000000000000");
        kube.nodes.add(node("ip-10-0-1-20.ec2.internal", true));
        kube.nodes.add(node("ip-10-0-1-21.ec2.internal", true));
    }

    private EksLoadBalancerController controller() {
        return new EksLoadBalancerController(kube, appliances, new EksLoadBalancerController.Names() {
            @Override
            public void put(Cluster target, String hostname, String address) {
                names.put(hostname, address);
            }

            @Override
            public void remove(Cluster target, String hostname) {
                names.remove(hostname);
            }
        }, clock, () -> delaySeconds);
    }

    private static final Map<String, String> BACKENDS = Map.of("ip-10-0-1-20.ec2.internal", "10.250.0.20",
            "ip-10-0-1-21.ec2.internal", "10.250.0.21");

    @Test
    void finalizerComesBeforeTheStatusAndTheHostnameResolvesToTheAppliance() {
        kube.add(service("web", Map.of(), null));
        EksLoadBalancerController controller = controller();

        controller.reconcile(cluster, REGION, BACKENDS);

        assertTrue(kube.patches.getFirst().contains("service.kubernetes.io/load-balancer-cleanup"), kube.patches.toString());
        JsonNode ingress = kube.status("web");
        String hostname = ingress.get(0).path("hostname").asText();
        assertTrue(hostname.matches("auidweb-\\d{10}\\.us-east-1\\.elb\\.amazonaws\\.com"), hostname);
        assertEquals("172.30.0.5", names.get(hostname));
        assertEquals(List.of("10.250.0.20", "10.250.0.21"), appliances.backends.get("lb-uid-web"));
        assertEquals(List.of("Normal/EnsuringLoadBalancer", "Normal/EnsuredLoadBalancer"), kube.events);
        assertEquals("TCP/80=127.0.0.1:41000", kube.annotation("web", EksLoadBalancerSpec.HOST_PORTS_ANNOTATION));
    }

    @Test
    void aPassStartsNoApplianceOnceItsClusterIsClosed() {
        kube.add(service("web", Map.of(), null));
        kube.add(service("api", Map.of(), null));
        EksLoadBalancerController controller = controller();

        controller.reconcile(cluster, REGION, BACKENDS, () -> !appliances.running.isEmpty());

        assertEquals(1, appliances.running.size(), "the cluster closed after the first appliance: "
                + appliances.running);
        controller.reconcile(cluster, REGION, BACKENDS, () -> true);
        assertEquals(1, appliances.running.size(), "a closed cluster's pass changes nothing");
        assertEquals(Map.of(), kube.published, "nor does it publish names to CoreDNS");
    }

    @Test
    void podsResolveTheNamesThroughCoreDnsUntilTheServiceGoes() {
        kube.add(service("web", Map.of(), null));
        EksLoadBalancerController controller = controller();
        controller.reconcile(cluster, REGION, BACKENDS);
        String hostname = kube.status("web").get(0).path("hostname").asText();
        assertEquals(Map.of(hostname, "172.30.0.5"), kube.published);

        kube.services.remove("web");
        controller.reconcile(cluster, REGION, BACKENDS);

        assertEquals(Map.of(), kube.published);
    }

    @Test
    void aPassThatChangesNothingWritesNothing() {
        kube.add(service("web", Map.of(), null));
        EksLoadBalancerController controller = controller();
        controller.reconcile(cluster, REGION, BACKENDS);
        int writes = kube.patches.size() + kube.statusPatches.size() + kube.events.size();

        controller.reconcile(cluster, REGION, BACKENDS);

        assertEquals(writes, kube.patches.size() + kube.statusPatches.size() + kube.events.size());
    }

    @Test
    void servicesOfAnotherClassAreLeftAlone() {
        kube.add(service("auto", Map.of(), "eks.amazonaws.com/nlb"));

        controller().reconcile(cluster, REGION, BACKENDS);

        assertTrue(kube.patches.isEmpty());
        assertTrue(kube.statusPatches.isEmpty());
        assertTrue(appliances.running.isEmpty());
    }

    @Test
    void anInvalidServiceStaysPendingWithOneSyncLoadBalancerFailedEvent() {
        ObjectNode service = service("pinned", Map.of(), null);
        ((ObjectNode) service.path("spec")).put("loadBalancerIP", "10.0.0.9");
        kube.add(service);
        EksLoadBalancerController controller = controller();

        controller.reconcile(cluster, REGION, BACKENDS);
        controller.reconcile(cluster, REGION, BACKENDS);

        assertTrue(kube.statusPatches.isEmpty());
        assertEquals(List.of("Warning/SyncLoadBalancerFailed"), kube.events);
        assertTrue(kube.messages.getFirst().endsWith("LoadBalancerIP cannot be specified for AWS ELB"));
    }

    @Test
    void deletingTheServiceRemovesItsApplianceAndNameThenTheFinalizer() {
        kube.add(service("web", Map.of(), null));
        EksLoadBalancerController controller = controller();
        controller.reconcile(cluster, REGION, BACKENDS);
        String hostname = kube.status("web").get(0).path("hostname").asText();
        ((ObjectNode) kube.services.get("web").path("metadata")).put("deletionTimestamp", "2026-11-11T10:01:00Z");

        controller.reconcile(cluster, REGION, BACKENDS);

        assertTrue(appliances.running.isEmpty());
        assertFalse(names.containsKey(hostname));
        assertFalse(kube.services.get("web").path("metadata").path("finalizers").toString()
                .contains("load-balancer-cleanup"));
        assertTrue(kube.events.containsAll(List.of("Normal/DeletingLoadBalancer", "Normal/DeletedLoadBalancer")));
    }

    @Test
    void aServiceThatStopsBeingALoadBalancerLosesItsStatusAndFinalizer() {
        kube.add(service("web", Map.of(), null));
        EksLoadBalancerController controller = controller();
        controller.reconcile(cluster, REGION, BACKENDS);
        ((ObjectNode) kube.services.get("web").path("spec")).put("type", "ClusterIP");

        controller.reconcile(cluster, REGION, BACKENDS);

        assertTrue(appliances.running.isEmpty());
        assertTrue(kube.status("web").isNull() || kube.status("web").isEmpty());
        assertFalse(kube.services.get("web").path("metadata").path("finalizers").toString()
                .contains("load-balancer-cleanup"));
    }

    @Test
    void theProvisioningDelayHoldsTheStatusAndTheHostnameSurvivesARestart() {
        delaySeconds = 30;
        kube.add(service("web", Map.of(), null));
        EksLoadBalancerController controller = controller();
        controller.reconcile(cluster, REGION, BACKENDS);
        assertTrue(kube.statusPatches.isEmpty(), "the load balancer is still provisioning");

        clock.advanceSeconds(31);
        controller.reconcile(cluster, REGION, BACKENDS);
        String hostname = kube.status("web").get(0).path("hostname").asText();

        delaySeconds = 0;
        controller().reconcile(cluster, REGION, BACKENDS);
        assertEquals(hostname, kube.status("web").get(0).path("hostname").asText());
        assertEquals(1, kube.statusPatches.size(), "a restarted controller finds the status it would write");
    }

    @Test
    void localTrafficPolicyForwardsOnlyToNodesWithAReadyEndpoint() {
        ObjectNode service = service("local", Map.of(), null);
        ((ObjectNode) service.path("spec")).put("externalTrafficPolicy", "Local");
        kube.add(service);
        ObjectNode slice = JSON.createObjectNode();
        slice.putObject("metadata").put("namespace", "default").putObject("labels")
                .put("kubernetes.io/service-name", "local");
        ObjectNode endpoint = slice.putArray("endpoints").addObject();
        endpoint.put("nodeName", "ip-10-0-1-21.ec2.internal");
        endpoint.putObject("conditions").put("ready", true);
        kube.slices.add(slice);

        controller().reconcile(cluster, REGION, BACKENDS);

        assertEquals(List.of("10.250.0.21"), appliances.backends.get("lb-uid-local"));
    }

    @Test
    void nodesNotReadyOrExcludedAreNoBackends() {
        kube.nodes.clear();
        kube.nodes.add(node("ip-10-0-1-20.ec2.internal", false));
        ObjectNode excluded = node("ip-10-0-1-21.ec2.internal", true);
        ((ObjectNode) excluded.path("metadata")).putObject("labels")
                .put("node.kubernetes.io/exclude-from-external-load-balancers", "true");
        kube.nodes.add(excluded);
        kube.add(service("web", Map.of(), null));

        controller().reconcile(cluster, REGION, BACKENDS);

        assertEquals(List.of(), appliances.backends.get("lb-uid-web"));
    }

    @Test
    void appliancesNoServiceHasAreRemoved() {
        appliances.running.add("lb-uid-gone");

        controller().reconcile(cluster, REGION, BACKENDS);

        assertTrue(appliances.running.isEmpty());
    }

    @Test
    void controllerNlbStatusCarriesItsPorts() {
        kube.add(service("nlb", Map.of(), "service.k8s.aws/nlb"));

        controller().reconcile(cluster, REGION, BACKENDS);

        JsonNode entry = kube.status("nlb").get(0);
        assertTrue(entry.path("hostname").asText().startsWith("k8s-default-nlb-"));
        assertEquals(80, entry.path("ports").get(0).path("port").asInt());
    }

    private static ObjectNode service(String name, Map<String, String> annotations, String loadBalancerClass) {
        ObjectNode service = JSON.createObjectNode();
        ObjectNode metadata = service.putObject("metadata");
        metadata.put("name", name);
        metadata.put("namespace", "default");
        metadata.put("uid", "uid-" + name);
        metadata.put("resourceVersion", "1");
        ObjectNode annotationNode = metadata.putObject("annotations");
        annotations.forEach(annotationNode::put);
        ObjectNode spec = service.putObject("spec");
        spec.put("type", "LoadBalancer");
        if (loadBalancerClass != null) {
            spec.put("loadBalancerClass", loadBalancerClass);
        }
        ObjectNode port = spec.putArray("ports").addObject();
        port.put("port", 80);
        port.put("protocol", "TCP");
        port.put("nodePort", 30080);
        return service;
    }

    private static ObjectNode node(String name, boolean ready) {
        ObjectNode node = JSON.createObjectNode();
        node.putObject("metadata").put("name", name);
        ObjectNode condition = node.putObject("status").putArray("conditions").addObject();
        condition.put("type", "Ready");
        condition.put("status", ready ? "True" : "False");
        return node;
    }

    private static final class FakeKube implements EksLoadBalancerController.Kube {
        private final Map<String, ObjectNode> services = new LinkedHashMap<>();
        private final List<JsonNode> nodes = new ArrayList<>();
        private final List<JsonNode> slices = new ArrayList<>();
        private final List<String> patches = new ArrayList<>();
        private final List<String> statusPatches = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private Map<String, String> published = Map.of();

        void add(ObjectNode service) {
            services.put(service.path("metadata").path("name").asText(), service);
        }

        JsonNode status(String name) {
            return services.get(name).path("status").path("loadBalancer").path("ingress");
        }

        String annotation(String name, String key) {
            return services.get(name).path("metadata").path("annotations").path(key).asText(null);
        }

        @Override
        public Optional<Snapshot> snapshot(Cluster cluster) {
            List<JsonNode> copies = new ArrayList<>();
            services.values().forEach(service -> copies.add(service.deepCopy()));
            return Optional.of(new Snapshot(copies, List.copyOf(nodes), List.copyOf(slices)));
        }

        @Override
        public boolean patchService(Cluster cluster, String namespace, String name, String mergePatch) {
            patches.add(mergePatch);
            merge(services.get(name), read(mergePatch));
            return true;
        }

        @Override
        public boolean patchServiceStatus(Cluster cluster, String namespace, String name, String mergePatch) {
            statusPatches.add(mergePatch);
            merge(services.get(name), read(mergePatch));
            return true;
        }

        @Override
        public void event(Cluster cluster, EksLoadBalancerSpec spec, String type, String reason, String message) {
            events.add(type + "/" + reason);
            messages.add(message);
        }

        @Override
        public void publishNames(Cluster cluster, String region, Map<String, String> addressesByHostname) {
            published = Map.copyOf(addressesByHostname);
        }

        private static JsonNode read(String json) {
            try {
                return JSON.readTree(json);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static void merge(ObjectNode target, JsonNode patch) {
            patch.fields().forEachRemaining(field -> {
                if (field.getValue().isNull()) {
                    target.remove(field.getKey());
                } else if (field.getValue().isObject() && target.path(field.getKey()).isObject()) {
                    merge((ObjectNode) target.get(field.getKey()), field.getValue());
                } else {
                    target.set(field.getKey(), field.getValue());
                }
            });
        }
    }

    private static final class FakeAppliances implements EksLoadBalancerController.Appliances {
        private final Set<String> running = new LinkedHashSet<>();
        private final Map<String, List<String>> backends = new HashMap<>();

        @Override
        public String nameFor(Cluster cluster, String serviceUid) {
            return "lb-" + serviceUid;
        }

        @Override
        public Running ensure(Cluster cluster, Spec spec) {
            running.add(spec.name());
            backends.put(spec.name(), spec.backends());
            return new Running("172.30.0.5", Map.of("TCP/80", 41000));
        }

        @Override
        public void remove(Cluster cluster, String name) {
            running.remove(name);
        }

        @Override
        public Set<String> list(Cluster cluster) {
            return Set.copyOf(running);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
