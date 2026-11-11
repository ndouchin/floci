package io.floci.aws.services.eks;

import io.floci.aws.core.common.AvailabilityZoneIds;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.services.ec2.Ec2ImageCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;
import io.floci.aws.services.eks.model.EksNode;
import io.floci.aws.services.eks.model.Nodegroup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What EKS gives a managed node, as pure functions: its instance ID, name, subnet and zone, provider ID, labels,
 * taints, pod capacity and AMI. The sources are in {@code docs/services/eks.md} and {@code EksNodeLayoutTest}.
 */
final class EksNodeLayout {

    /** The release date part of the release versions Floci reports; EKS's AMI releases carry their build date. */
    static final String RELEASE_DATE = "20251023";
    static final String INSTANCE_TYPE_LABEL = "node.kubernetes.io/instance-type";
    static final String BETA_INSTANCE_TYPE_LABEL = "beta.kubernetes.io/instance-type";
    static final String NODEGROUP_LABEL = "eks.amazonaws.com/nodegroup"; // partition-literal: Kubernetes label key
    static final String NODEGROUP_IMAGE_LABEL = "eks.amazonaws.com/nodegroup-image"; // partition-literal: Kubernetes label key
    static final String CAPACITY_TYPE_LABEL = "eks.amazonaws.com/capacityType"; // partition-literal: Kubernetes label key
    static final String GPU_RESOURCE = "nvidia.com/gpu";
    static final String RESOURCE_NAME_HOSTNAME = "resource-name";

    private static final int CLUSTER_SERVER_HOST = 10;
    private static final int FIRST_NODE_HOST = 20;
    private static final int LAST_NODE_HOST = 127;
    private static final int SMALL_INSTANCE_POD_CAP = 110;
    private static final int LARGE_INSTANCE_POD_CAP = 250;
    private static final int LARGE_INSTANCE_VCPUS = 30;
    private static final Pattern IMAGE_MINOR = Pattern.compile(":v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern RELEASE_MINOR = Pattern.compile("^(\\d+)\\.(\\d+)(?:[.-].*)?$");
    private static final Set<String> KUBELET_LABEL_NAMESPACES = Set.of("kubernetes.io", "k8s.io");
    private static final Set<String> KUBELET_ALLOWED_LABELS = Set.of("kubernetes.io/hostname", "kubernetes.io/arch",
            "kubernetes.io/os", BETA_INSTANCE_TYPE_LABEL, INSTANCE_TYPE_LABEL,
            "failure-domain.beta.kubernetes.io/region", "failure-domain.beta.kubernetes.io/zone",
            "topology.kubernetes.io/region", "topology.kubernetes.io/zone", "beta.kubernetes.io/arch",
            "beta.kubernetes.io/os");
    private static final Set<String> KUBELET_ALLOWED_LABEL_DOMAINS = Set.of("kubelet.kubernetes.io",
            "node.kubernetes.io");

    private EksNodeLayout() {}

    static String ipNodeName(String address, String region) {
        return AwsRegions.ec2PrivateIpDnsName(address, region);
    }

    /** Resolver options of a node: ndots at the resolver's default, whatever the machine Floci runs on uses. */
    static final List<String> DNS_OPTIONS = List.of("ndots:1");

    /** The search domain of a node's resolver: the domain name of the VPC's default DHCP option set. */
    static List<String> dnsSearch(String region) {
        return List.of(AwsRegions.ec2PrivateDnsDomain(region));
    }

    static String resourceNodeName(String instanceId, String region) {
        return instanceId + "." + AwsRegions.ec2PrivateDnsDomain(region);
    }

    /** The node's name: its EC2 private DNS name, of the hostname type its launch template asks for (IP name by default). */
    static String nodeName(String address, String instanceId, String region, String hostnameType) {
        return RESOURCE_NAME_HOSTNAME.equalsIgnoreCase(hostnameType) || address == null
                ? resourceNodeName(instanceId, region) : ipNodeName(address, region);
    }

    static String instanceId(String nodegroupArn, int launchIndex) {
        String uuid = nodegroupArn == null ? "" : nodegroupArn.substring(nodegroupArn.lastIndexOf('/') + 1);
        return "i-" + sha256Hex(uuid + "#" + launchIndex).substring(0, 17);
    }

    static String providerId(String availabilityZone, String instanceId) {
        return "aws:///" + availabilityZone + "/" + instanceId;
    }

    /** The subnet a new node launches in: the one holding the fewest of the node group's nodes, in request order. */
    static String nextSubnet(List<String> subnets, List<EksNode> nodes) {
        if (subnets == null || subnets.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (EksNode node : nodes) {
            if (node.getSubnetId() != null) {
                counts.merge(node.getSubnetId(), 1, Integer::sum);
            }
        }
        String best = subnets.getFirst();
        for (String subnet : subnets) {
            if (counts.getOrDefault(subnet, 0) < counts.getOrDefault(best, 0)) {
                best = subnet;
            }
        }
        return best;
    }

    /** The nodes a scale-in terminates: the newest, taken from the zone holding the most nodes first. */
    static List<EksNode> nodesToRemove(List<EksNode> nodes, int count) {
        List<EksNode> remaining = new ArrayList<>(nodes);
        List<EksNode> removed = new ArrayList<>();
        for (int i = 0; i < count && !remaining.isEmpty(); i++) {
            Map<String, Integer> perZone = new HashMap<>();
            remaining.forEach(node -> perZone.merge(String.valueOf(node.getAvailabilityZone()), 1, Integer::sum));
            int busiest = perZone.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            EksNode victim = remaining.stream()
                    .filter(node -> perZone.get(String.valueOf(node.getAvailabilityZone())) == busiest)
                    .max(Comparator.comparingInt(EksNode::getLaunchIndex))
                    .orElseThrow();
            remaining.remove(victim);
            removed.add(victim);
        }
        return removed;
    }

    /** The pods a node of these instance types holds: the smallest VPC CNI figure under the managed node group cap. */
    static int maxPods(List<String> instanceTypes, Ec2InstanceTypeCatalog catalog) {
        if (instanceTypes == null || instanceTypes.isEmpty()) {
            return SMALL_INSTANCE_POD_CAP;
        }
        int smallest = Integer.MAX_VALUE;
        for (String instanceType : instanceTypes) {
            smallest = Math.min(smallest, catalog.find(instanceType).map(EksNodeLayout::maxPods)
                    .orElse(SMALL_INSTANCE_POD_CAP));
        }
        return smallest;
    }

    static int maxPods(CatalogInstanceType type) {
        int cap = type.vcpu > LARGE_INSTANCE_VCPUS ? LARGE_INSTANCE_POD_CAP : SMALL_INSTANCE_POD_CAP;
        int interfaces = type.networkCards == null ? 0 : type.networkCards.stream()
                .filter(card -> card.networkCardIndex != null && type.defaultNetworkCardIndex != null
                        && card.networkCardIndex.intValue() == type.defaultNetworkCardIndex.intValue()
                        && card.maximumNetworkInterfaces != null)
                .mapToInt(card -> card.maximumNetworkInterfaces)
                .findFirst().orElse(0);
        int addresses = type.ipv4AddressesPerInterface == null ? 0 : type.ipv4AddressesPerInterface;
        if (interfaces <= 0 || addresses <= 0) {
            return cap;
        }
        return Math.min(cap, interfaces * (addresses - 1) + 2);
    }

    static int gpus(String instanceType, Ec2InstanceTypeCatalog catalog) {
        return catalog.find(instanceType).map(type -> type.gpus).orElse(0);
    }

    /** {@code <major>.<minor>.<patch>-<date>}, the patch being the k3s release Floci runs for that minor. */
    static String releaseVersion(String kubernetesVersion) {
        if (kubernetesVersion == null) {
            return null;
        }
        String image = EksClusterManager.SUPPORTED_K8S_VERSIONS.get(kubernetesVersion);
        Matcher matcher = image == null ? null : IMAGE_MINOR.matcher(image);
        String patch = matcher != null && matcher.find()
                ? matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3)
                : kubernetesVersion + ".0";
        return patch + "-" + RELEASE_DATE;
    }

    /** The Kubernetes minor a release version names, or null when it names none (a custom AMI's ID). */
    static String releaseKubernetesMinor(String releaseVersion) {
        Matcher matcher = releaseVersion == null ? null : RELEASE_MINOR.matcher(releaseVersion.trim());
        return matcher != null && matcher.matches() ? matcher.group(1) + "." + matcher.group(2) : null;
    }

    /** The Kubernetes minor a k3s image's tag names, or null for a tag such as {@code latest}. */
    static String imageMinor(String image) {
        Matcher matcher = image == null ? null : IMAGE_MINOR.matcher(image);
        return matcher != null && matcher.find() ? matcher.group(1) + "." + matcher.group(2) : null;
    }

    /**
     * The AMI type of a node group created without one: Amazon Linux 2023 from Kubernetes 1.30, Amazon Linux 2
     * before, for the instance type's architecture, in its NVIDIA variant for a GPU instance type.
     */
    static String defaultAmiType(String kubernetesVersion, List<String> instanceTypes, Ec2InstanceTypeCatalog catalog) {
        String first = instanceTypes == null || instanceTypes.isEmpty() ? null : instanceTypes.getFirst();
        Optional<CatalogInstanceType> type = catalog.find(first);
        boolean arm = type.map(t -> t.supportedArchitectures.contains("arm64")).orElse(false);
        boolean gpu = type.map(t -> t.gpus > 0).orElse(false);
        boolean al2023 = minor(kubernetesVersion) >= 30;
        if (al2023) {
            return (arm ? "AL2023_ARM_64_" : "AL2023_x86_64_") + (gpu ? "NVIDIA" : "STANDARD");
        }
        if (gpu && !arm) {
            return "AL2_x86_64_GPU";
        }
        return arm ? "AL2_ARM_64" : "AL2_x86_64";
    }

    /**
     * The AMI a node boots: the launch template's image when it names one, else the recommended EKS optimized AMI
     * of the AMI type and Kubernetes version. Floci's image catalog publishes the Amazon Linux 2023 and 2 AMIs; any
     * other AMI type, and Amazon Linux 2 past 1.32, take the Amazon Linux 2023 AMI of the same architecture.
     */
    static String amiId(String amiType, String kubernetesVersion, String launchTemplateImage, Ec2ImageCatalog images) {
        if (launchTemplateImage != null && !launchTemplateImage.isBlank()) {
            return launchTemplateImage.trim();
        }
        String type = amiType == null ? "" : amiType.toUpperCase(Locale.ROOT);
        String arch = type.contains("ARM") ? "arm64" : "x86_64";
        String prefix = "/aws/service/eks/optimized-ami/" + kubernetesVersion;
        List<String> candidates = new ArrayList<>();
        if ("AL2_X86_64".equals(type)) {
            candidates.add(prefix + "/amazon-linux-2/recommended/image_id");
        }
        candidates.add(prefix + "/amazon-linux-2023/" + arch + "/standard/recommended/image_id");
        candidates.add("/aws/service/eks/optimized-ami/" + EksService.DEFAULT_K8S_VERSION
                + "/amazon-linux-2023/" + arch + "/standard/recommended/image_id");
        for (String parameter : candidates) {
            Optional<Ec2ImageCatalog.CatalogImage> image = images.findByPublicParameterName(parameter);
            if (image.isPresent()) {
                return image.get().imageId;
            }
        }
        return images.images().getFirst().imageId;
    }

    /**
     * The labels a node registers with: the node group, its AMI and capacity type, its launch template when the
     * user supplied one, the zone, region and zone ID of its subnet, its instance type, and the node group's own
     * labels minus the keys EKS keeps for itself.
     */
    static Map<String, String> registrationLabels(Nodegroup group, EksNode node, String region) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("topology.kubernetes.io/region", region);
        if (node.getAvailabilityZone() != null) {
            labels.put("topology.kubernetes.io/zone", node.getAvailabilityZone());
            labels.put("topology.k8s.aws/zone-id", node.getAvailabilityZoneId() != null
                    ? node.getAvailabilityZoneId() : AvailabilityZoneIds.forZoneName(region, node.getAvailabilityZone()));
        }
        if (node.getInstanceType() != null) {
            labels.put(INSTANCE_TYPE_LABEL, node.getInstanceType());
            labels.put(BETA_INSTANCE_TYPE_LABEL, node.getInstanceType());
        }
        labels.put(NODEGROUP_LABEL, group.getNodegroupName());
        if (node.getAmiId() != null) {
            labels.put(NODEGROUP_IMAGE_LABEL, node.getAmiId());
        }
        labels.put(CAPACITY_TYPE_LABEL, group.getCapacityType() != null && !group.getCapacityType().isBlank()
                ? group.getCapacityType() : "ON_DEMAND");
        if (group.getLaunchTemplate() instanceof Map<?, ?> template) {
            Object id = template.get("id");
            Object version = template.get("version");
            if (id != null && !id.toString().isBlank()) {
                labels.put("eks.amazonaws.com/sourceLaunchTemplateId", id.toString()); // partition-literal: Kubernetes label key
            }
            if (version != null && !version.toString().isBlank()) {
                labels.put("eks.amazonaws.com/sourceLaunchTemplateVersion", version.toString()); // partition-literal: Kubernetes label key
            }
        }
        if (group.getLabels() != null) {
            group.getLabels().forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !isReservedLabel(key)) {
                    labels.put(key, value);
                }
            });
        }
        return labels;
    }

    static boolean isReservedLabel(String key) {
        return key.startsWith("topology.kubernetes.io/")
                || key.startsWith("eks.amazonaws.com/") // partition-literal: Kubernetes label prefix
                || INSTANCE_TYPE_LABEL.equals(key);
    }

    /** Whether NodeRestriction lets a kubelet set this label on its own Node at registration. */
    static boolean kubeletMaySetLabel(String key) {
        int slash = key.indexOf('/');
        if (slash < 0) {
            return true;
        }
        String domain = key.substring(0, slash);
        if (domain.equals("node-restriction.kubernetes.io") || domain.endsWith(".node-restriction.kubernetes.io")) {
            return false;
        }
        boolean restricted = KUBELET_LABEL_NAMESPACES.stream()
                .anyMatch(namespace -> domain.equals(namespace) || domain.endsWith("." + namespace));
        if (!restricted) {
            return true;
        }
        return KUBELET_ALLOWED_LABELS.contains(key) || KUBELET_ALLOWED_LABEL_DOMAINS.stream()
                .anyMatch(allowed -> domain.equals(allowed) || domain.endsWith("." + allowed));
    }

    static List<String> registrationTaints(Nodegroup group) {
        List<String> taints = new ArrayList<>();
        for (Map<String, String> taint : EksClusterManager.nodegroupTaints(group)) {
            String value = taint.get("value");
            taints.add(taint.get("key") + (value != null ? "=" + value : "") + ":" + taint.get("effect"));
        }
        return taints;
    }

    /** The nodes that get an agent: the first {@code cap} in node group creation order, every node with no cap. */
    static List<EksNode> underCap(List<List<EksNode>> groupsInCreationOrder, int cap) {
        List<EksNode> running = new ArrayList<>();
        for (List<EksNode> nodes : groupsInCreationOrder) {
            for (EksNode node : nodes) {
                if (cap <= 0 || running.size() < cap) {
                    running.add(node);
                }
            }
        }
        return running;
    }

    static String nodePassword(String joinToken, String nodeName) {
        return sha256Hex(joinToken + "/" + nodeName).substring(0, 32);
    }

    static String serverAddress(String subnet) {
        return hostAddress(subnet, CLUSTER_SERVER_HOST);
    }

    /** The upper half of the cluster network, where Docker hands out addresses; nodes and the server sit below it. */
    static String dynamicRange(String subnet) {
        return hostAddress(subnet, 128) + "/25";
    }

    /** The lowest node address of the cluster network nobody in {@code used} holds, or null when none is left. */
    static String nextClusterAddress(String subnet, List<String> used) {
        for (int host = FIRST_NODE_HOST; host <= LAST_NODE_HOST; host++) {
            String address = hostAddress(subnet, host);
            if (!used.contains(address)) {
                return address;
            }
        }
        return null;
    }

    private static String hostAddress(String subnet, int host) {
        String network = subnet.substring(0, subnet.indexOf('/'));
        return network.substring(0, network.lastIndexOf('.') + 1) + host;
    }

    private static int minor(String version) {
        Matcher matcher = version == null ? null : RELEASE_MINOR.matcher(version);
        return matcher != null && matcher.matches() ? Integer.parseInt(matcher.group(2)) : 0;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
