package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Info;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AvailabilityZoneIds;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.core.common.docker.UserDataPipeline;
import io.floci.aws.services.ec2.Ec2ImageCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.eks.EksNodeReconciler.NodeLaunchException;
import io.floci.aws.services.eks.EksNodeReconciler.NodeState;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.EksNode;
import io.floci.aws.services.eks.model.Nodegroup;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The managed nodes of multi-node clusters as k3s agent containers: one per node, on the cluster's network at a
 * fixed address, on the VPC's network at the address leased from the node's subnet, and on the emulator's network
 * when Floci runs in a container. Each registers under its EC2 private DNS name with the labels, taints, provider
 * ID and pod capacity EKS gives a managed node.
 */
final class EksNodeAgents implements EksNodeReconciler.Runtime {

    private static final Logger LOG = Logger.getLogger(EksNodeAgents.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String NODEGROUP_LABEL = "io.floci.eks.nodegroup";
    static final String INSTANCE_ID_LABEL = "io.floci.eks.instance-id";
    static final String NODE_NAME_LABEL = "io.floci.eks.node-name";
    static final String NODE_VOLUME_LABEL = "io.floci.eks.node-volume";
    static final String NODE_SPEC_LABEL = "io.floci.eks.node-spec";
    static final String EXCLUDE_FROM_LOAD_BALANCERS = "node.kubernetes.io/exclude-from-external-load-balancers";
    private static final String UNINITIALIZED_TAINT = "node.cloudprovider.kubernetes.io/uninitialized";
    private static final String NODE_PASSWORD_DIR = "/etc/rancher/node";
    private static final String CLUSTER_IP_ENV = "FLOCI_CLUSTER_IP";
    private static final int KUBECTL_TIMEOUT_SECONDS = 30;
    private static final Duration HOST_INFO_TTL = Duration.ofMinutes(1);

    /**
     * The agent's entrypoint: flannel carries pod traffic over the cluster network, the one every agent of the
     * cluster shares, whichever network Docker made the default route; the interface holding the node's cluster
     * network address is passed as {@code --flannel-iface}. The k3s image has busybox {@code sh} and {@code ip}.
     */
    static final String AGENT_SCRIPT = "IFACE=$(ip -o -4 addr show 2>/dev/null | while read -r _ ifc _ addr _; do "
            + "[ \"${addr%/*}\" = \"$" + CLUSTER_IP_ENV + "\" ] && echo \"${ifc%@*}\"; done); "
            + "[ -n \"$IFACE\" ] && set -- \"$@\" --flannel-iface=\"$IFACE\"; exec /bin/k3s \"$@\"";
    static final String RSHARE_PREFIX = "mount --make-rshared / || echo 'floci: WARN: mount --make-rshared / failed;"
            + " external CNI may not work' >&2; ";

    private final EksClusterManager manager;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerBuilder containerBuilder;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final Ec2InstanceTypeCatalog instanceTypes;
    private final Ec2ImageCatalog images;
    private volatile HostInfo hostInfo;

    private record HostInfo(long memoryBytes, int cpus, long readAtMillis) {}

    EksNodeAgents(EksClusterManager manager, ContainerLifecycleManager lifecycleManager,
                  ContainerBuilder containerBuilder, ContainerDetector containerDetector, EmulatorConfig config,
                  Ec2InstanceTypeCatalog instanceTypes, Ec2ImageCatalog images) {
        this.manager = manager;
        this.lifecycleManager = lifecycleManager;
        this.containerBuilder = containerBuilder;
        this.containerDetector = containerDetector;
        this.config = config;
        this.instanceTypes = instanceTypes;
        this.images = images;
    }

    @Override
    public void placeNode(Cluster cluster, Nodegroup group, EksNode node, Set<String> clusterAddressesInUse) {
        String region = manager.clusterRegion(cluster);
        String accountId = manager.resolveClusterAccountId(cluster);
        Ec2Service ec2 = manager.ec2();
        Subnet subnet = null;
        if (ec2 != null && node.getSubnetId() != null) {
            try {
                subnet = RequestScopes.callAs(accountId, region, () -> ec2.requireSubnet(region, node.getSubnetId()));
            } catch (RuntimeException e) {
                LOG.debugv("Subnet {0} of EKS node group {1} is unknown to EC2: {2}", node.getSubnetId(),
                        group.getNodegroupName(), e.getMessage());
            }
        }
        if (subnet != null && subnet.getAvailabilityZone() != null) {
            node.setAvailabilityZone(subnet.getAvailabilityZone());
            node.setAvailabilityZoneId(subnet.getAvailabilityZoneId());
            node.setVpcId(subnet.getVpcId());
        } else {
            node.setAvailabilityZone(region + "a");
            node.setAvailabilityZoneId(AvailabilityZoneIds.forZoneName(region, region + "a"));
            node.setVpcId(cluster.getResourcesVpcConfig() != null ? cluster.getResourcesVpcConfig().getVpcId() : null);
        }
        String clusterIp = cluster.getNodeSubnet() == null ? null
                : EksNodeLayout.nextClusterAddress(cluster.getNodeSubnet(), List.copyOf(clusterAddressesInUse));
        node.setClusterIp(clusterIp);
        if (clusterIp != null) {
            clusterAddressesInUse.add(clusterIp);
        }
        Optional<String> leased = Optional.empty();
        if (subnet != null && ec2.isVpcNetworkEnabled()) {
            String subnetId = node.getSubnetId();
            leased = RequestScopes.callAs(accountId, region, () -> ec2.allocateVpcPrivateIp(region, subnetId));
        }
        node.setVpcAddress(leased.isPresent());
        node.setPrivateIp(leased.orElse(clusterIp));
        LaunchTemplateData template = launchTemplate(cluster, group.getLaunchTemplate());
        String hostnameType = template != null && template.getPrivateDnsNameOptions() != null
                ? template.getPrivateDnsNameOptions().getHostnameType() : null;
        node.setNodeName(EksNodeLayout.nodeName(node.getPrivateIp(), node.getInstanceId(), region, hostnameType));
        node.setImage(manager.nodeImage(cluster, node.getVersion()));
        String amiType = group.getAmiType() != null ? group.getAmiType()
                : EksNodeLayout.defaultAmiType(node.getVersion(), group.getInstanceTypes(), instanceTypes);
        node.setAmiId(EksNodeLayout.amiId(amiType, node.getVersion(), template != null ? template.getImageId() : null,
                images));
        node.setContainerName(manager.agentContainerName(cluster, node.getInstanceId()));
        node.setVolumeName(node.getContainerName());
    }

    /** Re-leases the VPC address a stored node holds, after a restart emptied the in-memory lease table. */
    void reclaimAddress(Cluster cluster, EksNode node) {
        Ec2Service ec2 = manager.ec2();
        if (!node.isVpcAddress() || ec2 == null || node.getSubnetId() == null || node.getPrivateIp() == null) {
            return;
        }
        String region = manager.clusterRegion(cluster);
        RequestScopes.runAs(manager.resolveClusterAccountId(cluster), region,
                () -> ec2.claimVpcPrivateIp(region, node.getSubnetId(), node.getPrivateIp()));
    }

    @Override
    public Optional<String> readJoinToken(Cluster cluster) {
        return manager.readJoinToken(cluster);
    }

    @Override
    public boolean ensureAgent(Cluster cluster, Nodegroup group, EksNode node) {
        if (node.getClusterIp() == null) {
            throw new NodeLaunchException("InsufficientFreeAddresses", "The network of EKS cluster "
                    + cluster.getName() + " has no address left for node " + node.getInstanceId(), false);
        }
        String name = node.getContainerName() != null ? node.getContainerName()
                : manager.agentContainerName(cluster, node.getInstanceId());
        String volume = node.getVolumeName() != null ? node.getVolumeName() : name;
        List<String> args = agentArgs(cluster, group, node);
        EksNodeCapacity.Limits limits = capacity(cluster, group, node);
        String serverUrl = serverUrl(cluster);
        String spec = specHash(node.getImage(), serverUrl, cluster.getJoinToken(), args, limits, node);

        Optional<Container> existing = lifecycleManager.findByName(name);
        if (existing.isPresent()) {
            Map<String, String> labels = existing.get().getLabels() != null ? existing.get().getLabels() : Map.of();
            if (spec.equals(labels.get(NODE_SPEC_LABEL))) {
                boolean running = "running".equalsIgnoreCase(existing.get().getState());
                if (!running) {
                    lifecycleManager.adopt(existing.get().getId(), List.of());
                    LOG.infov("Started the stopped k3s agent {0} of node {1}", name, node.getNodeName());
                }
                if (!running || !manager.isAgentRegistered(node.getInstanceId())) {
                    manager.registerAgentNode(cluster, group, node, existing.get().getId());
                }
                return false;
            }
            LOG.infov("Recreating the k3s agent {0} of node {1}: its specification changed", name, node.getNodeName());
            lifecycleManager.removeIfExistsStrict(name, true);
        }

        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                "eks", node.getInstanceId(), manager.resolveClusterAccountId(cluster), manager.clusterRegion(cluster)));
        labels.put(EksClusterManager.CLUSTER_LABEL, cluster.getDockerName());
        labels.put(EksClusterManager.ROLE_LABEL, EksClusterManager.NODE_ROLE);
        labels.put(NODEGROUP_LABEL, group.getNodegroupName());
        labels.put(INSTANCE_ID_LABEL, node.getInstanceId());
        labels.put(NODE_NAME_LABEL, node.getNodeName());
        labels.put(NODE_VOLUME_LABEL, volume);
        labels.put(NODE_SPEC_LABEL, spec);
        List<String> cmd = new ArrayList<>();
        cmd.add("floci-k3s");
        cmd.addAll(args);
        String script = (config.services().eks().disableCni() ? RSHARE_PREFIX : "") + AGENT_SCRIPT;
        ContainerBuilder.Builder builder = containerBuilder.newContainer(node.getImage())
                .withName(name)
                .withEntrypoint(List.of("sh", "-c", script))
                .withCmd(cmd)
                .withEnv("K3S_URL", serverUrl)
                .withEnv("K3S_TOKEN", cluster.getJoinToken())
                .withEnv(CLUSTER_IP_ENV, node.getClusterIp())
                .withNetworkMode(cluster.getNodeNetwork())
                .withNamedVolume(volume, EksClusterManager.K3S_DATA_DIR)
                .withPrivileged(true)
                .withLogRotation()
                .withLabels(labels);
        if (limits != null) {
            if (limits.memoryBytes() > 0) {
                builder.withMemoryBytes(limits.memoryBytes());
            }
            if (limits.vcpus() > 0) {
                builder.withCpuUnits(limits.vcpus() * 1024);
            }
        }
        if (config.services().eks().embeddedDns()) {
            builder.withEmbeddedDns();
        }
        if (config.services().eks().ecrRegistryMirror() && config.services().ecr().enabled()) {
            builder.withHostDockerInternalOnLinux();
        }
        ContainerSpec containerSpec = builder.build();
        String containerId = lifecycleManager.create(containerSpec);
        try {
            lifecycleManager.reconnectToNetwork(containerId, cluster.getNodeNetwork(), node.getClusterIp());
            RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, "/",
                    NODE_PASSWORD_DIR.substring(1) + "/password",
                    EksNodeLayout.nodePassword(cluster.getJoinToken(), node.getNodeName()).getBytes(StandardCharsets.UTF_8),
                    0600);
            manager.injectEcrRegistryMirror(containerId, cluster);
            manager.linkContainerdCertsDir(containerId, cluster.getName());
            if (node.isVpcAddress()) {
                attachToVpc(cluster, node, containerId);
            }
            if (containerDetector.isRunningInContainer()) {
                Optional<String> emulatorNetwork = containerBuilder.resolveDockerNetwork(
                        config.services().eks().dockerNetwork());
                if (emulatorNetwork.isPresent() && !emulatorNetwork.get().equals(cluster.getNodeNetwork())) {
                    lifecycleManager.connectToNetwork(containerId, emulatorNetwork.get());
                }
            }
            lifecycleManager.startCreated(containerId, containerSpec);
        } catch (RuntimeException e) {
            try {
                lifecycleManager.removeIfExistsStrict(name, true);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new NodeLaunchException("AsgInstanceLaunchFailures", "Could not launch node "
                    + node.getInstanceId() + ": " + e.getMessage(), false);
        }
        LOG.infov("Started k3s agent {0} for node {1} ({2}) of EKS node group {3}", name, node.getNodeName(),
                node.getPrivateIp(), group.getNodegroupName());
        manager.registerAgentNode(cluster, group, node, containerId);
        runUserData(cluster, group, node, containerId);
        return true;
    }

    /**
     * Where agents join the server: its fixed address on the cluster network, which its certificate names. An
     * address rather than its container name keeps the join off DNS, whose search domains the host decides.
     */
    static String serverUrl(Cluster cluster) {
        return "https://" + (cluster.getServerAddress() != null ? cluster.getServerAddress() : cluster.getDockerName())
                + ":6443";
    }

    private void attachToVpc(Cluster cluster, EksNode node, String containerId) {
        Ec2Service ec2 = manager.ec2();
        String region = manager.clusterRegion(cluster);
        Optional<String> attached = ec2 == null ? Optional.empty() : RequestScopes.callAs(
                manager.resolveClusterAccountId(cluster), region, () -> ec2.attachContainerToVpc(region,
                        node.getVpcId(), node.getSubnetId(), containerId, node.getPrivateIp()));
        if (attached.isEmpty()) {
            throw new IllegalStateException("could not attach it to the network of VPC " + node.getVpcId()
                    + " at " + node.getPrivateIp());
        }
    }

    /** EC2 runs a launch template's user data on each instance it launches; a script that fails keeps the node out. */
    private void runUserData(Cluster cluster, Nodegroup group, EksNode node, String containerId) {
        LaunchTemplateData template = launchTemplate(cluster, group.getLaunchTemplate());
        if (template == null || template.getUserData() == null || template.getUserData().isBlank()) {
            return;
        }
        UserDataPipeline.ExecutionResult result = manager.executeUserData(containerId,
                "EKS node " + node.getNodeName() + " (node group " + group.getNodegroupName() + ")",
                template.getUserData());
        if (result != null && !result.isSuccess()) {
            throw new NodeLaunchException("NodeCreationFailure", result.getFailureMessage() != null
                    ? result.getFailureMessage() : "User data failed on node " + node.getNodeName(), true);
        }
    }

    List<String> agentArgs(Cluster cluster, Nodegroup group, EksNode node) {
        String region = manager.clusterRegion(cluster);
        List<String> args = new ArrayList<>(List.of("agent",
                "--node-name=" + node.getNodeName(),
                "--node-ip=" + node.getPrivateIp(),
                "--kubelet-arg=provider-id=" + EksNodeLayout.providerId(node.getAvailabilityZone(), node.getInstanceId()),
                "--kubelet-arg=max-pods=" + EksNodeLayout.maxPods(group.getInstanceTypes(), instanceTypes)));
        EksNodeLayout.registrationLabels(group, node, region).forEach((key, value) -> {
            if (EksNodeLayout.kubeletMaySetLabel(key)) {
                args.add("--node-label=" + key + "=" + value);
            }
        });
        for (String taint : EksNodeLayout.registrationTaints(group)) {
            args.add("--node-taint=" + taint);
        }
        EksNodeCapacity.Limits limits = capacity(cluster, group, node);
        if (limits != null) {
            limits.addKubeletArgs(args);
        }
        args.addAll(EksClusterManager.agentCallerArgs(manager.resolveCallerArgs(cluster)));
        return args;
    }

    /**
     * The node's Docker memory and CPU limits and kubelet reservations, from its instance type, or from
     * {@code floci.services.eks.node-instance-type} when set; a type the catalog lacks is sized as
     * {@value EksClusterManager#DEFAULT_NODE_INSTANCE_TYPE}, while the node keeps its requested type's label.
     */
    EksNodeCapacity.Limits capacity(Cluster cluster, Nodegroup group, EksNode node) {
        String sizing = config.services().eks().nodeInstanceType().map(String::trim).filter(type -> !type.isEmpty())
                .filter(type -> instanceTypes.find(type).isPresent())
                .orElse(node.getInstanceType());
        CatalogInstanceType type = instanceTypes.find(sizing)
                .or(() -> instanceTypes.find(EksClusterManager.DEFAULT_NODE_INSTANCE_TYPE)).orElse(null);
        if (type == null) {
            return null;
        }
        HostInfo host = hostInfo();
        int maxMemoryMib = config.services().eks().maxMemoryMib();
        int maxVcpus = config.services().eks().maxVcpus();
        if (host == null) {
            return maxMemoryMib <= 0 && maxVcpus <= 0 ? null
                    : EksNodeCapacity.explicitCeilingWithoutHostInfo(maxMemoryMib,
                            maxVcpus > 0 ? Math.min(type.vcpu, maxVcpus) : 0);
        }
        return EksNodeCapacity.calculate(type, EksNodeLayout.maxPods(group.getInstanceTypes(), instanceTypes),
                host.memoryBytes(), host.cpus(), maxMemoryMib, maxVcpus);
    }

    private HostInfo hostInfo() {
        HostInfo cached = hostInfo;
        if (cached != null && System.currentTimeMillis() - cached.readAtMillis() < HOST_INFO_TTL.toMillis()) {
            return cached;
        }
        try {
            Info info = lifecycleManager.getDockerClient().infoCmd().exec();
            HostInfo read = new HostInfo(info.getMemTotal() == null ? 0 : info.getMemTotal(),
                    info.getNCPU() == null ? 0 : info.getNCPU(), System.currentTimeMillis());
            hostInfo = read;
            return read;
        } catch (RuntimeException e) {
            LOG.debugv("Docker host capacity unavailable for EKS nodes: {0}", e.getMessage());
            return cached;
        }
    }

    @Override
    public void removeAgent(Cluster cluster, EksNode node, boolean terminate) {
        String name = node.getContainerName() != null ? node.getContainerName()
                : manager.agentContainerName(cluster, node.getInstanceId());
        lifecycleManager.removeIfExistsStrict(name, true);
        if (node.getNodeName() != null && cluster.getContainerId() != null) {
            kubectl(cluster, List.of("delete", "node", node.getNodeName(), "--ignore-not-found", "--wait=false"));
        }
        manager.unregisterAgentNode(cluster, node);
        if (terminate) {
            lifecycleManager.removeVolume(node.getVolumeName() != null ? node.getVolumeName() : name);
            Ec2Service ec2 = manager.ec2();
            if (node.isVpcAddress() && ec2 != null) {
                String region = manager.clusterRegion(cluster);
                RequestScopes.runAs(manager.resolveClusterAccountId(cluster), region,
                        () -> ec2.releaseVpcPrivateIp(region, node.getSubnetId(), node.getPrivateIp()));
            }
            LOG.infov("Terminated node {0} ({1}) of EKS cluster {2}", node.getInstanceId(), node.getNodeName(),
                    cluster.getName());
        }
    }

    @Override
    public Optional<Map<String, NodeState>> nodeStates(Cluster cluster) {
        ContainerExec.Result result = kubectl(cluster, List.of("get", "nodes", "-o", "json"));
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        try {
            Map<String, NodeState> states = new LinkedHashMap<>();
            for (JsonNode item : JSON.readTree(result.stdout()).path("items")) {
                boolean ready = false;
                for (JsonNode condition : item.path("status").path("conditions")) {
                    if ("Ready".equals(condition.path("type").asText())) {
                        ready = "True".equals(condition.path("status").asText());
                    }
                }
                boolean uninitialized = false;
                for (JsonNode taint : item.path("spec").path("taints")) {
                    uninitialized |= UNINITIALIZED_TAINT.equals(taint.path("key").asText());
                }
                states.put(item.path("metadata").path("name").asText(), new NodeState(ready, uninitialized,
                        item.path("status").path("nodeInfo").path("kubeletVersion").asText(null)));
            }
            return Optional.of(states);
        } catch (IOException e) {
            LOG.debugv("Unreadable node list from EKS cluster {0}: {1}", cluster.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * What EKS puts on a node after it joins: the node group labels a kubelet may not set on itself and every
     * label and taint changed since it registered (round 1's managed-annotation patch), and, on a GPU instance
     * type, {@code nvidia.com/gpu} capacity. EKS's NVIDIA AMIs leave that to a device plugin, which cannot run
     * without a GPU, so Floci sets it on the node status directly: a documented deviation.
     */
    @Override
    public boolean finishNode(Cluster cluster, Nodegroup group, EksNode node) {
        Map<String, String> labels = EksNodeLayout.registrationLabels(group, node, manager.clusterRegion(cluster));
        if (!manager.applyNodeLabelsAndTaints(cluster, node.getNodeName(), labels,
                EksClusterManager.nodegroupTaints(group))) {
            return false;
        }
        int gpus = EksNodeLayout.gpus(node.getInstanceType(), instanceTypes);
        if (gpus > 0) {
            String quantity = "\"" + EksNodeLayout.GPU_RESOURCE + "\":\"" + gpus + "\"";
            return kubectl(cluster, List.of("patch", "node", node.getNodeName(), "--subresource=status",
                    "--type=merge", "-p", "{\"status\":{\"capacity\":{" + quantity + "},\"allocatable\":{"
                            + quantity + "}}}")).exitCode() == 0;
        }
        return true;
    }

    @Override
    public boolean drainNode(Cluster cluster, EksNode node, int timeoutSeconds) {
        kubectl(cluster, List.of("label", "node", node.getNodeName(), EXCLUDE_FROM_LOAD_BALANCERS + "=true",
                "--overwrite"));
        kubectl(cluster, List.of("cordon", node.getNodeName()));
        ContainerExec.Result drained = manager.execInContainerForResult(cluster.getContainerId(), new String[]{
                "kubectl", "drain", node.getNodeName(), "--ignore-daemonsets", "--delete-emptydir-data", "--force",
                "--timeout=" + timeoutSeconds + "s"}, timeoutSeconds + KUBECTL_TIMEOUT_SECONDS);
        if (drained.exitCode() != 0) {
            LOG.warnv("Node {0} of EKS cluster {1} did not drain within {2} seconds: {3}", node.getNodeName(),
                    cluster.getName(), String.valueOf(timeoutSeconds), drained.stdout());
            return false;
        }
        return true;
    }

    @Override
    public void uncordonNode(Cluster cluster, EksNode node) {
        kubectl(cluster, List.of("uncordon", node.getNodeName()));
        kubectl(cluster, List.of("label", "node", node.getNodeName(), EXCLUDE_FROM_LOAD_BALANCERS + "-"));
    }

    @Override
    public void removeOrphanAgents(Cluster cluster, Set<String> instanceIds) {
        for (Container container : agentsOf(cluster)) {
            Map<String, String> labels = container.getLabels() != null ? container.getLabels() : Map.of();
            if (instanceIds.contains(labels.get(INSTANCE_ID_LABEL))) {
                continue;
            }
            LOG.infov("Removing k3s agent {0} of EKS cluster {1}: no node of the cluster has it",
                    labels.get(INSTANCE_ID_LABEL), cluster.getName());
            removeAgentContainer(cluster, container);
        }
    }

    /** Removes every agent of the cluster, found by label so one started during a delete goes too. */
    void removeAllAgents(Cluster cluster) {
        for (Container container : agentsOf(cluster)) {
            removeAgentContainer(cluster, container);
        }
    }

    private void removeAgentContainer(Cluster cluster, Container container) {
        Map<String, String> labels = container.getLabels() != null ? container.getLabels() : Map.of();
        lifecycleManager.removeIfExistsStrict(container.getId(), true);
        String nodeName = labels.get(NODE_NAME_LABEL);
        if (nodeName != null && cluster.getContainerId() != null) {
            kubectl(cluster, List.of("delete", "node", nodeName, "--ignore-not-found", "--wait=false"));
        }
        if (labels.get(NODE_VOLUME_LABEL) != null) {
            lifecycleManager.removeVolume(labels.get(NODE_VOLUME_LABEL));
        }
    }

    private List<Container> agentsOf(Cluster cluster) {
        if (cluster.getDockerName() == null) {
            return List.of();
        }
        return lifecycleManager.listByLabels(Map.of(EksClusterManager.CLUSTER_LABEL, cluster.getDockerName(),
                EksClusterManager.ROLE_LABEL, EksClusterManager.NODE_ROLE));
    }

    @Override
    public void authorizeNodeRole(Cluster cluster, Nodegroup group) {
        if (cluster.getAccessConfig() != null && !"CONFIG_MAP".equals(cluster.getAccessConfig().authenticationMode())) {
            return;
        }
        ContainerExec.Result current = kubectl(cluster, List.of("-n", "kube-system", "get", "configmap", "aws-auth",
                "-o", "json"));
        String mapRoles = "";
        if (current.exitCode() == 0) {
            try {
                mapRoles = JSON.readTree(current.stdout()).path("data").path("mapRoles").asText("");
            } catch (IOException e) {
                LOG.warnv("Not mapping node role {0}: the aws-auth ConfigMap of EKS cluster {1} does not parse",
                        group.getNodeRole(), cluster.getName());
                return;
            }
        } else if (!current.stdout().contains("NotFound")) {
            return;
        }
        String merged = EksAwsAuthConfigMap.withNodeRole(mapRoles, group.getNodeRole());
        if (merged == null) {
            return;
        }
        ContainerExec.Result written = current.exitCode() == 0
                ? kubectl(cluster, List.of("-n", "kube-system", "patch", "configmap", "aws-auth", "--type=merge",
                        "-p", JSON.createObjectNode().set("data", JSON.createObjectNode().put("mapRoles", merged))
                                .toString()))
                : kubectl(cluster, List.of("-n", "kube-system", "create", "configmap", "aws-auth",
                        "--from-literal=mapRoles=" + merged));
        if (written.exitCode() == 0) {
            LOG.infov("Mapped node role {0} in the aws-auth ConfigMap of EKS cluster {1}", group.getNodeRole(),
                    cluster.getName());
        }
    }

    @Override
    public void deleteStaleNodes(Cluster cluster, Set<String> nodeNames, Map<String, NodeState> present) {
        for (String name : present.keySet()) {
            if (!nodeNames.contains(name) && !manager.isJoinedNode(cluster, name)) {
                LOG.infov("Deleting Kubernetes node {0} of EKS cluster {1}: no node of the cluster has it", name,
                        cluster.getName());
                kubectl(cluster, List.of("delete", "node", name, "--ignore-not-found", "--wait=false"));
            }
        }
    }

    private LaunchTemplateData launchTemplate(Cluster cluster, Object launchTemplate) {
        Ec2Service ec2 = manager.ec2();
        if (!(launchTemplate instanceof Map<?, ?> map) || ec2 == null) {
            return null;
        }
        String id = map.get("id") != null ? map.get("id").toString() : null;
        String name = map.get("name") != null ? map.get("name").toString() : null;
        String version = map.get("version") != null ? map.get("version").toString() : null;
        String region = manager.clusterRegion(cluster);
        try {
            return RequestScopes.callAs(manager.resolveClusterAccountId(cluster), region,
                    () -> ec2.resolveLaunchTemplateData(region, id, name, version));
        } catch (RuntimeException e) {
            LOG.debugv("Launch template of an EKS node group could not be read: {0}", e.getMessage());
            return null;
        }
    }

    private ContainerExec.Result kubectl(Cluster cluster, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        command.addAll(args);
        try {
            ContainerExec.Result result = manager.execInContainerForResult(cluster.getContainerId(),
                    command.toArray(String[]::new), KUBECTL_TIMEOUT_SECONDS);
            if (result.exitCode() != 0) {
                LOG.debugv("kubectl {0} on EKS cluster {1} failed: {2}", args, cluster.getName(), result.stdout());
            }
            return result;
        } catch (RuntimeException e) {
            LOG.debugv("kubectl {0} on EKS cluster {1} failed: {2}", args, cluster.getName(), e.getMessage());
            return new ContainerExec.Result(-1, String.valueOf(e.getMessage()), "", false);
        }
    }

    private static String specHash(String image, String serverUrl, String token, List<String> args,
                                   EksNodeCapacity.Limits limits, EksNode node) {
        StringBuilder spec = new StringBuilder();
        spec.append(image).append('\n').append(serverUrl).append('\n').append(token).append('\n')
                .append(node.getClusterIp()).append('\n').append(node.getPrivateIp()).append('\n')
                .append(limits == null ? "unbounded" : limits.memoryBytes() + ":" + limits.vcpus()).append('\n');
        for (String arg : args) {
            if (!arg.startsWith("--node-label=") && !arg.startsWith("--node-taint=")) {
                spec.append(arg).append('\n');
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(spec.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
