package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.eks.model.Cluster;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The load balancer controller's hands: kubectl in the cluster's server for the API, and one appliance container per
 * Service for the load balancer itself. The appliance is the cluster's own k3s image (already pulled; it ships
 * {@code /bin/aux/iptables}) on the cluster network, joining Floci's network too when Floci runs in a container; it
 * DNATs each Service port to the backend nodes' NodePorts, picked at random, masquerading so replies come back through
 * it, and publishes each TCP port on the Docker host's {@code 127.0.0.1}. It runs with {@code NET_ADMIN} and
 * {@code net.ipv4.ip_forward=1}, not privileged. A running appliance is kept while its specification label matches,
 * and otherwise replaced, asking again for the host ports the old one had so they stay stable.
 */
final class EksLoadBalancerRuntime implements EksLoadBalancerController.Kube, EksLoadBalancerController.Appliances {

    private static final Logger LOG = Logger.getLogger(EksLoadBalancerRuntime.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String LOAD_BALANCER_ROLE = "load-balancer";
    static final String SERVICE_LABEL = "io.floci.eks.load-balancer";
    static final String SPEC_LABEL = "io.floci.eks.load-balancer-spec";
    private static final String IPTABLES = "/bin/aux/iptables";
    private static final String EVENT_ENV = "FLOCI_EVENT";
    private static final String NAMES_ENV = "FLOCI_LB_NAMES";
    static final String COREDNS_CUSTOM = "coredns-custom";
    static final String COREDNS_KEY = "floci-load-balancers.server";
    static final String COREDNS_HOSTS_KEY = "floci-load-balancers.hosts";
    private static final String HOSTS_ENV = "FLOCI_LB_HOSTS";
    private static final int KUBECTL_TIMEOUT_SECONDS = 30;

    private final EksClusterManager manager;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerBuilder containerBuilder;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final Map<String, String> publishedBlocks = new ConcurrentHashMap<>();

    EksLoadBalancerRuntime(EksClusterManager manager, ContainerLifecycleManager lifecycleManager,
                           ContainerBuilder containerBuilder, ContainerDetector containerDetector, EmulatorConfig config) {
        this.manager = manager;
        this.lifecycleManager = lifecycleManager;
        this.containerBuilder = containerBuilder;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    @Override
    public Optional<Snapshot> snapshot(Cluster cluster) {
        ContainerExec.Result result = kubectl(cluster, List.of("get", "services,nodes,endpointslices", "-A", "-o",
                "json"));
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        try {
            List<JsonNode> services = new ArrayList<>();
            List<JsonNode> nodes = new ArrayList<>();
            List<JsonNode> slices = new ArrayList<>();
            for (JsonNode item : JSON.readTree(result.stdout()).path("items")) {
                switch (item.path("kind").asText()) {
                    case "Service" -> services.add(item);
                    case "Node" -> nodes.add(item);
                    case "EndpointSlice" -> slices.add(item);
                    default -> {
                        // Nothing else is asked for.
                    }
                }
            }
            return Optional.of(new Snapshot(services, nodes, slices));
        } catch (IOException e) {
            LOG.debugv("Unreadable objects from EKS cluster {0}: {1}", cluster.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean patchService(Cluster cluster, String namespace, String name, String mergePatch) {
        return kubectl(cluster, List.of("-n", namespace, "patch", "service", name, "--type=merge", "-p", mergePatch))
                .exitCode() == 0;
    }

    @Override
    public boolean patchServiceStatus(Cluster cluster, String namespace, String name, String mergePatch) {
        return kubectl(cluster, List.of("-n", namespace, "patch", "service", name, "--subresource=status",
                "--type=merge", "-p", mergePatch)).exitCode() == 0;
    }

    /**
     * kubectl has no command that creates an Event, so the Event goes in as a manifest, carried in an environment
     * variable and piped to {@code kubectl create -f -}, from the {@code service-controller} component as upstream.
     */
    @Override
    public void event(Cluster cluster, EksLoadBalancerSpec spec, String type, String reason, String message) {
        String now = Instant.now().toString();
        ObjectNode event = JSON.createObjectNode();
        event.put("apiVersion", "v1");
        event.put("kind", "Event");
        event.putObject("metadata").put("generateName", spec.name() + ".").put("namespace", spec.namespace());
        event.putObject("involvedObject").put("apiVersion", "v1").put("kind", "Service")
                .put("namespace", spec.namespace()).put("name", spec.name()).put("uid", spec.uid());
        event.put("type", type);
        event.put("reason", reason);
        event.put("message", message);
        event.putObject("source").put("component", "service-controller");
        event.put("firstTimestamp", now);
        event.put("lastTimestamp", now);
        event.put("count", 1);
        try {
            ContainerExec.Result result = shell(cluster, "printf %s \"$" + EVENT_ENV + "\" | kubectl create -f -",
                    List.of(EVENT_ENV + "=" + event));
            if (result.exitCode() != 0) {
                LOG.debugv("Could not record event {0} on Service {1}/{2}: {3}", reason, spec.namespace(),
                        spec.name(), result.summary());
            }
        } catch (RuntimeException e) {
            LOG.debugv("Could not record event {0} on Service {1}/{2}: {3}", reason, spec.namespace(), spec.name(),
                    e.getMessage());
        }
    }

    /**
     * Writes the cluster's load balancer names into the {@code coredns-custom} ConfigMap, under keys of Floci's own, so
     * keys a user put there stay: a server block for the region's ELB zones, which k3s's CoreDNS imports
     * ({@code import /etc/coredns/custom/*.server}) when it loads its configuration, and the hosts file it answers
     * from, which the {@code hosts} plugin rereads every 5 seconds once the kubelet has synced the ConfigMap. The block
     * goes in on the cluster's first pass, before CoreDNS has a node to start on; CoreDNS pods already running when
     * Floci creates the ConfigMap are restarted once, rather than left to notice a file that appeared after they
     * started.
     */
    @Override
    public void publishNames(Cluster cluster, String region, Map<String, String> addressesByHostname) {
        String block = coreDnsServerBlock(region);
        String hosts = coreDnsHosts(addressesByHostname);
        String key = cluster.getDockerName();
        String published = block + "\n" + hosts;
        if (published.equals(publishedBlocks.get(key))) {
            return;
        }
        String patch = JSON.createObjectNode().set("data", JSON.createObjectNode().put(COREDNS_KEY, block)
                .put(COREDNS_HOSTS_KEY, hosts)).toString();
        ContainerExec.Result patched = kubectl(cluster, List.of("-n", "kube-system", "patch", "configmap",
                COREDNS_CUSTOM, "--type=merge", "-p", patch));
        boolean written = patched.exitCode() == 0;
        if (!written && patched.stdout().contains("NotFound")) {
            boolean coreDnsRunning = !kubectl(cluster, List.of("-n", "kube-system", "get", "pods", "-l",
                    "k8s-app=kube-dns", "-o", "name")).stdout().isBlank();
            written = shell(cluster, "kubectl -n kube-system create configmap " + COREDNS_CUSTOM
                    + " --from-literal=" + COREDNS_KEY + "=\"$" + NAMES_ENV + "\""
                    + " --from-literal=" + COREDNS_HOSTS_KEY + "=\"$" + HOSTS_ENV + "\"",
                    List.of(NAMES_ENV + "=" + block, HOSTS_ENV + "=" + hosts)).exitCode() == 0;
            if (written && coreDnsRunning) {
                kubectl(cluster, List.of("-n", "kube-system", "rollout", "restart", "deployment/coredns"));
            }
        }
        if (written) {
            publishedBlocks.put(key, published);
        }
    }

    /** A CoreDNS server block for the region's two ELB DNS zones, answering from the hosts file Floci keeps. */
    static String coreDnsServerBlock(String region) {
        String suffix = AwsRegions.dnsSuffixFor(region);
        return region + ".elb." + suffix + ":53 elb." + region + "." + suffix + ":53 {\n"
                + "    hosts /etc/coredns/custom/" + COREDNS_HOSTS_KEY + " {\n"
                + "        ttl 30\n"
                + "        reload 5s\n"
                + "        fallthrough\n"
                + "    }\n"
                + "    forward . /etc/resolv.conf\n"
                + "}\n";
    }

    /** The hosts file of the load balancer names: each name with its appliance's address. */
    static String coreDnsHosts(Map<String, String> addressesByHostname) {
        StringBuilder hosts = new StringBuilder();
        new TreeMap<>(addressesByHostname).forEach((hostname, address) ->
                hosts.append(address).append(' ').append(hostname).append('\n'));
        return hosts.toString();
    }

    private ContainerExec.Result shell(Cluster cluster, String script, List<String> env) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            ContainerExec.Result result = ContainerExec.run(lifecycleManager.getDockerClient(),
                    cluster.getContainerId(), new String[]{"sh", "-c", script}, env, KUBECTL_TIMEOUT_SECONDS, stdout,
                    stderr);
            return new ContainerExec.Result(result.exitCode(), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8) + result.stderr(), result.timedOut());
        } catch (RuntimeException e) {
            return new ContainerExec.Result(-1, "", String.valueOf(e.getMessage()), false);
        }
    }

    void forget(Cluster cluster) {
        if (cluster.getDockerName() != null) {
            publishedBlocks.remove(cluster.getDockerName());
        }
    }

    @Override
    public String nameFor(Cluster cluster, String serviceUid) {
        return manager.loadBalancerContainerName(cluster, serviceUid);
    }

    @Override
    public Running ensure(Cluster cluster, Spec spec) {
        String hostPortsMode = config.services().eks().loadBalancer().hostPorts();
        ContainerSpec wanted = containerSpec(cluster, spec, Map.of(), hostPortsMode, true);
        String digest = wanted.labels().get(SPEC_LABEL);
        Optional<Container> existing = lifecycleManager.findByName(spec.name());
        Map<String, Integer> previous = Map.of();
        if (existing.isPresent()) {
            Map<String, String> labels = existing.get().getLabels() != null ? existing.get().getLabels() : Map.of();
            previous = publishedHostPorts(existing.get().getId());
            if ("running".equalsIgnoreCase(existing.get().getState()) && digest.equals(labels.get(SPEC_LABEL))) {
                return running(existing.get().getId(), cluster);
            }
            lifecycleManager.removeIfExists(existing.get().getId());
        }
        String containerId;
        try {
            containerId = start(cluster, containerSpec(cluster, spec, previous, hostPortsMode, true));
        } catch (RuntimeException first) {
            LOG.debugv("Appliance {0} could not take host ports {1}, retrying with any: {2}", spec.name(), previous,
                    first.getMessage());
            containerId = start(cluster, containerSpec(cluster, spec, Map.of(), hostPortsMode, false));
        }
        Running running = running(containerId, cluster);
        LOG.infov("Started load balancer appliance {0} for Service {1} of EKS cluster {2} at {3}, host ports {4}",
                spec.name(), spec.serviceUid(), cluster.getName(), running.address(), running.hostPorts());
        return running;
    }

    private String start(Cluster cluster, ContainerSpec spec) {
        lifecycleManager.removeIfExists(spec.name());
        String containerId = lifecycleManager.create(spec);
        try {
            emulatorNetwork(cluster).ifPresent(network -> lifecycleManager.connectToNetwork(containerId, network));
            lifecycleManager.startCreated(containerId, spec);
            return containerId;
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(containerId);
            throw e;
        }
    }

    private Optional<String> emulatorNetwork(Cluster cluster) {
        if (!containerDetector.isRunningInContainer()) {
            return Optional.empty();
        }
        return containerBuilder.resolveDockerNetwork(config.services().eks().dockerNetwork())
                .filter(network -> !network.equals(cluster.getNodeNetwork()) && !Set.of("host", "none", "bridge")
                        .contains(network));
    }

    /**
     * The appliance's address: on Floci's network when Floci runs in a container, where Floci and the other
     * containers it runs reach it; on the cluster network otherwise, where the nodes and their pods reach it.
     */
    private Running running(String containerId, Cluster cluster) {
        InspectContainerResponse inspect = lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec();
        Map<String, ContainerNetwork> networks = inspect.getNetworkSettings() != null
                && inspect.getNetworkSettings().getNetworks() != null
                ? inspect.getNetworkSettings().getNetworks() : Map.of();
        String address = null;
        Optional<String> emulator = emulatorNetwork(cluster);
        if (emulator.isPresent() && networks.get(emulator.get()) != null) {
            address = networks.get(emulator.get()).getIpAddress();
        }
        if ((address == null || address.isBlank()) && networks.get(cluster.getNodeNetwork()) != null) {
            address = networks.get(cluster.getNodeNetwork()).getIpAddress();
        }
        return new Running(address, publishedHostPorts(inspect));
    }

    private Map<String, Integer> publishedHostPorts(String containerId) {
        try {
            return publishedHostPorts(lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec());
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static Map<String, Integer> publishedHostPorts(InspectContainerResponse inspect) {
        Map<String, Integer> hostPorts = new LinkedHashMap<>();
        Ports ports = inspect.getNetworkSettings() != null ? inspect.getNetworkSettings().getPorts() : null;
        if (ports == null || ports.getBindings() == null) {
            return hostPorts;
        }
        for (Map.Entry<ExposedPort, Ports.Binding[]> entry : ports.getBindings().entrySet()) {
            if (entry.getValue() == null || entry.getValue().length == 0) {
                continue;
            }
            try {
                hostPorts.put(entry.getKey().getProtocol().name() + "/" + entry.getKey().getPort(),
                        Integer.parseInt(entry.getValue()[0].getHostPortSpec()));
            } catch (NumberFormatException ignored) {
                // A binding Docker has not resolved to a number yet publishes nothing usable.
            }
        }
        return hostPorts;
    }

    @Override
    public void remove(Cluster cluster, String name) {
        lifecycleManager.removeIfExists(name);
    }

    @Override
    public Set<String> list(Cluster cluster) {
        Set<String> names = new LinkedHashSet<>();
        for (Container container : appliancesOf(cluster)) {
            if (container.getNames() != null && container.getNames().length > 0) {
                String name = container.getNames()[0];
                names.add(name.startsWith("/") ? name.substring(1) : name);
            }
        }
        return names;
    }

    /** Removes every appliance of the cluster, found by label, with the cluster. */
    void removeAll(Cluster cluster) {
        for (Container container : appliancesOf(cluster)) {
            lifecycleManager.removeIfExists(container.getId());
        }
    }

    private List<Container> appliancesOf(Cluster cluster) {
        if (cluster.getDockerName() == null) {
            return List.of();
        }
        return lifecycleManager.listByLabels(Map.of(EksClusterManager.CLUSTER_LABEL, cluster.getDockerName(),
                EksClusterManager.ROLE_LABEL, LOAD_BALANCER_ROLE));
    }

    ContainerSpec containerSpec(Cluster cluster, Spec spec, Map<String, Integer> hostPorts, String hostPortsMode,
                                boolean honorHostPorts) {
        String script = script(spec);
        String image = manager.resolveClusterImage(cluster);
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                "eks", spec.serviceUid(), manager.resolveClusterAccountId(cluster), manager.clusterRegion(cluster)));
        labels.put(EksClusterManager.CLUSTER_LABEL, cluster.getDockerName());
        labels.put(EksClusterManager.ROLE_LABEL, LOAD_BALANCER_ROLE);
        labels.put(SERVICE_LABEL, spec.serviceUid());
        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName(spec.name())
                .withEntrypoint(List.of("sh", "-c", script))
                .withNetworkMode(cluster.getNodeNetwork())
                .withCapAdd("NET_ADMIN")
                .withSysctl("net.ipv4.ip_forward", "1")
                .withLogRotation();
        if (!"none".equals(hostPortsMode)) {
            for (EksLoadBalancerSpec.Port port : spec.ports()) {
                if (!"TCP".equalsIgnoreCase(port.protocol())) {
                    continue;
                }
                int hostPort = 0;
                if (honorHostPorts) {
                    hostPort = "same-as-service".equals(hostPortsMode) ? port.port()
                            : hostPorts.getOrDefault(port.key(), 0);
                }
                builder.withLoopbackPortBinding(port.port(), hostPort);
            }
        }
        labels.put(SPEC_LABEL, digest(String.join("\n", image, String.valueOf(cluster.getNodeNetwork()),
                emulatorNetwork(cluster).orElse(""), hostPortsMode, script)));
        return builder.withLabels(labels).build();
    }

    /** The appliance's {@code sh -c} script: the NAT rules, then sleep. Every value in it is an address or a number. */
    static String script(Spec spec) {
        StringBuilder script = new StringBuilder("set -e\nIPT=" + IPTABLES + "\n");
        List<String> backends = spec.backends();
        for (EksLoadBalancerSpec.Port port : spec.ports()) {
            String protocol = port.protocol().toLowerCase(Locale.ROOT);
            List<String> sources = spec.sourceRanges().isEmpty() ? Collections.singletonList(null) : spec.sourceRanges();
            for (String source : sources) {
                for (int i = 0; i < backends.size(); i++) {
                    script.append("$IPT -t nat -A PREROUTING -p ").append(protocol);
                    if (source != null) {
                        script.append(" -s ").append(source);
                    }
                    script.append(" --dport ").append(port.port());
                    if (i < backends.size() - 1) {
                        script.append(" -m statistic --mode random --probability ")
                                .append(String.format(Locale.ROOT, "%.10f", 1.0 / (backends.size() - i)));
                    }
                    script.append(" -j DNAT --to-destination ").append(backends.get(i)).append(':')
                            .append(port.nodePort()).append('\n');
                }
            }
            for (String backend : backends) {
                script.append("$IPT -t nat -A POSTROUTING -p ").append(protocol).append(" -d ").append(backend)
                        .append(" --dport ").append(port.nodePort()).append(" -j MASQUERADE\n");
            }
        }
        return script.append("exec sleep infinity\n").toString();
    }

    private ContainerExec.Result kubectl(Cluster cluster, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        command.addAll(args);
        try {
            return manager.execInContainerForResult(cluster.getContainerId(), command.toArray(String[]::new),
                    KUBECTL_TIMEOUT_SECONDS);
        } catch (RuntimeException e) {
            return new ContainerExec.Result(-1, String.valueOf(e.getMessage()), "", false);
        }
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
