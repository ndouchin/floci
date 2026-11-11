package io.floci.aws.services.eks;

import io.floci.aws.services.ec2.Ec2ImageCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog;
import io.floci.aws.services.eks.model.EksNode;
import io.floci.aws.services.eks.model.Nodegroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One row per fact of the multi-node map's section 1, with the source it comes from. */
class EksNodeLayoutTest {

    private static final String ARN = "arn:aws:eks:us-east-1:000000000000:nodegroup/demo/workers/"
            + "0a1b2c3d-1111-2222-3333-444455556666";

    private final Ec2InstanceTypeCatalog types = new Ec2InstanceTypeCatalog();
    private final Ec2ImageCatalog images = new Ec2ImageCatalog();

    // EC2 hostname types: IP names are ip-<a-b-c-d>.ec2.internal in us-east-1 and
    // ip-<a-b-c-d>.<region>.compute.internal elsewhere; resource names use the instance ID.
    // https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/hostname-types.html
    @ParameterizedTest
    @CsvSource({
            "10.0.1.23, us-east-1, ip-10-0-1-23.ec2.internal",
            "10.24.34.0, us-west-2, ip-10-24-34-0.us-west-2.compute.internal",
            "192.168.3.238, eu-west-1, ip-192-168-3-238.eu-west-1.compute.internal"
    })
    void ipNodeNameFollowsTheEc2IpNameOfTheRegion(String address, String region, String expected) {
        assertEquals(expected, EksNodeLayout.ipNodeName(address, region));
    }

    // A node resolves names as an instance with the VPC's default DHCP option set does: its domain name, ec2.internal
    // in us-east-1 and <region>.compute.internal elsewhere, completes unqualified names, and ndots keeps its default
    // of 1 (no search domain of the machine Floci runs on, whose ndots:5 multiplies every lookup).
    // https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateDhcpOptions.html
    @ParameterizedTest
    @CsvSource({"us-east-1, ec2.internal", "eu-west-1, eu-west-1.compute.internal"})
    void nodesSearchTheVpcsDhcpDomainWithTheDefaultNdots(String region, String domain) {
        assertEquals(List.of(domain), EksNodeLayout.dnsSearch(region));
        assertEquals(List.of("ndots:1"), EksNodeLayout.DNS_OPTIONS);
    }

    @Test
    void resourceNodeNameIsTheInstanceIdInTheRegionsDomain() {
        assertEquals("i-0123456789abcdef0.ec2.internal", EksNodeLayout.resourceNodeName("i-0123456789abcdef0", "us-east-1"));
        assertEquals("i-0123456789abcdef0.us-west-2.compute.internal",
                EksNodeLayout.resourceNodeName("i-0123456789abcdef0", "us-west-2"));
    }

    @Test
    void launchTemplateResourceNameHostnameTypeNamesTheNodeAfterItsInstance() {
        assertEquals("i-0123456789abcdef0.ec2.internal",
                EksNodeLayout.nodeName("10.0.1.5", "i-0123456789abcdef0", "us-east-1", "resource-name"));
        assertEquals("ip-10-0-1-5.ec2.internal",
                EksNodeLayout.nodeName("10.0.1.5", "i-0123456789abcdef0", "us-east-1", "ip-name"));
        assertEquals("ip-10-0-1-5.ec2.internal",
                EksNodeLayout.nodeName("10.0.1.5", "i-0123456789abcdef0", "us-east-1", null));
    }

    // An instance ID is i- and 17 hex digits; a replacement instance gets a new one.
    // https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_Instance.html
    @Test
    void instanceIdsAreStablePerLaunchAndNewForEachReplacement() {
        String first = EksNodeLayout.instanceId(ARN, 0);
        assertTrue(first.matches("i-[0-9a-f]{17}"), first);
        assertEquals(first, EksNodeLayout.instanceId(ARN, 0));
        assertNotEquals(first, EksNodeLayout.instanceId(ARN, 1));
        assertNotEquals(first, EksNodeLayout.instanceId(ARN.replace("0a1b2c3d", "9a1b2c3d"), 0));
    }

    // nodeadm sets the kubelet provider ID to aws:///<availability-zone>/<instance-id>.
    // https://github.com/awslabs/amazon-eks-ami/blob/cb447b971ee8dcf121c4f12887455ca21590ff5d/nodeadm/internal/kubelet/config.go#L401-L403
    @Test
    void providerIdNamesTheZoneAndTheInstance() {
        assertEquals("aws:///us-east-1b/i-0123456789abcdef0", EksNodeLayout.providerId("us-east-1b", "i-0123456789abcdef0"));
    }

    // Managed nodes are instances of an Auto Scaling group spread over the node group's subnets.
    // https://docs.aws.amazon.com/eks/latest/userguide/managed-node-groups.html
    @Test
    void newNodesGoToTheSubnetWithFewestNodesInRequestOrder() {
        List<String> subnets = List.of("subnet-a", "subnet-b", "subnet-c");
        List<EksNode> nodes = new ArrayList<>();
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String subnet = EksNodeLayout.nextSubnet(subnets, nodes);
            chosen.add(subnet);
            EksNode node = new EksNode();
            node.setSubnetId(subnet);
            nodes.add(node);
        }
        assertEquals(List.of("subnet-a", "subnet-b", "subnet-c", "subnet-a", "subnet-b"), chosen);
        nodes.removeIf(node -> "subnet-a".equals(node.getSubnetId()));
        assertEquals("subnet-a", EksNodeLayout.nextSubnet(subnets, nodes));
    }

    @Test
    void scaleInRemovesTheNewestNodesFromTheBusiestZoneFirst() {
        List<EksNode> nodes = new ArrayList<>();
        nodes.add(node("i-a0", 0, "us-east-1a"));
        nodes.add(node("i-b1", 1, "us-east-1b"));
        nodes.add(node("i-a2", 2, "us-east-1a"));
        nodes.add(node("i-b3", 3, "us-east-1b"));
        nodes.add(node("i-a4", 4, "us-east-1a"));

        List<EksNode> removed = EksNodeLayout.nodesToRemove(nodes, 2);

        assertEquals(List.of("i-a4", "i-b3"), removed.stream().map(EksNode::getInstanceId).toList());
    }

    // Max pods: ENIs on the default network card * (IPv4 per ENI - 1) + 2, then the managed node group cap of
    // 110 under 30 vCPUs and 250 above, and the smallest across the node group's instance types.
    // https://github.com/awslabs/amazon-eks-ami/blob/cb447b971ee8dcf121c4f12887455ca21590ff5d/nodeadm/internal/kubelet/eni_max_pods.go#L63-L79
    // https://docs.aws.amazon.com/eks/latest/userguide/choosing-instance-type.html
    // https://docs.aws.amazon.com/eks/latest/userguide/cni-increase-ip-addresses.html
    @ParameterizedTest
    @CsvSource({
            "t3.medium, 17",
            "t3.large, 35",
            "m5.large, 29",
            "m5.xlarge, 58",
            "m5.4xlarge, 110",
            "g5.xlarge, 58",
            "g4dn.xlarge, 29",
            "g5.12xlarge, 250"
    })
    void maxPodsFollowsTheVpcCniFormulaUnderTheManagedNodeGroupCap(String instanceType, int expected) {
        assertEquals(expected, EksNodeLayout.maxPods(List.of(instanceType), types));
    }

    @Test
    void maxPodsOfSeveralInstanceTypesIsTheSmallest() {
        assertEquals(17, EksNodeLayout.maxPods(List.of("m5.xlarge", "t3.medium", "m5.large"), types));
    }

    @Test
    void maxPodsOfAnUnknownInstanceTypeIs110() {
        assertEquals(110, EksNodeLayout.maxPods(List.of("x9.unknown"), types));
    }

    // Release versions read k8s_major.k8s_minor.k8s_patch-release_date, for example 1.31.0-20241024.
    // https://docs.aws.amazon.com/eks/latest/userguide/eks-linux-ami-versions.html
    @Test
    void releaseVersionTakesEksAmiReleaseShapeFromTheK3sPatch() {
        assertTrue(EksNodeLayout.releaseVersion("1.33").matches("1\\.33\\.1-\\d{8}"), EksNodeLayout.releaseVersion("1.33"));
        assertTrue(EksNodeLayout.releaseVersion("1.34").matches("1\\.34\\.1-\\d{8}"));
        assertTrue(EksNodeLayout.releaseVersion("1.40").matches("1\\.40\\.0-\\d{8}"));
    }

    @Test
    void kubernetesVersionOfAReleaseVersionIsItsMinor() {
        assertEquals("1.33", EksNodeLayout.releaseKubernetesMinor("1.33.1-20251023"));
        assertEquals("1.31", EksNodeLayout.releaseKubernetesMinor("1.31-eks-1"));
        assertEquals(null, EksNodeLayout.releaseKubernetesMinor("ami-0abc"));
    }

    // From Kubernetes 1.30, new managed node groups default to Amazon Linux 2023.
    // https://docs.aws.amazon.com/eks/latest/userguide/al2023.html
    @ParameterizedTest
    @CsvSource({
            "1.29, t3.medium, AL2_x86_64",
            "1.29, m6g.large, AL2_ARM_64",
            "1.30, t3.medium, AL2023_x86_64_STANDARD",
            "1.33, m6g.large, AL2023_ARM_64_STANDARD",
            "1.33, g5.xlarge, AL2023_x86_64_NVIDIA",
            "1.29, g5.xlarge, AL2_x86_64_GPU"
    })
    void defaultAmiTypeFollowsTheVersionAndTheInstanceType(String version, String instanceType, String expected) {
        assertEquals(expected, EksNodeLayout.defaultAmiType(version, List.of(instanceType), types));
    }

    // The nodegroup-image label carries the AMI ID the node booted from: EKS's recommended AMI for the AMI type
    // and version, published under /aws/service/eks/optimized-ami/<version>/..., or the launch template's image.
    // https://docs.aws.amazon.com/eks/latest/userguide/ml-workload-placement.html
    // https://docs.aws.amazon.com/eks/latest/userguide/retrieve-ami-id.html
    @Test
    void nodegroupImageIsTheRecommendedAmiOfTheAmiTypeOrTheLaunchTemplateImage() {
        String al2023 = images.findByPublicParameterName(
                "/aws/service/eks/optimized-ami/1.33/amazon-linux-2023/x86_64/standard/recommended/image_id")
                .orElseThrow().imageId;
        assertEquals(al2023, EksNodeLayout.amiId("AL2023_x86_64_STANDARD", "1.33", null, images));
        assertEquals(al2023, EksNodeLayout.amiId("AL2023_x86_64_NVIDIA", "1.33", null, images));
        String al2 = images.findByPublicParameterName(
                "/aws/service/eks/optimized-ami/1.29/amazon-linux-2/recommended/image_id").orElseThrow().imageId;
        assertEquals(al2, EksNodeLayout.amiId("AL2_x86_64", "1.29", null, images));
        assertEquals(al2023, EksNodeLayout.amiId("AL2_x86_64", "1.33", null, images));
        assertEquals("ami-0123456789abcdef0", EksNodeLayout.amiId("CUSTOM", "1.33", "ami-0123456789abcdef0", images));
        assertTrue(EksNodeLayout.amiId("AL2023_ARM_64_STANDARD", "1.33", null, images).startsWith("ami-"));
    }

    // Labels managed nodes carry: topology, instance type, and the eks.amazonaws.com/* labels of the node group.
    // https://docs.aws.amazon.com/eks/latest/userguide/ml-workload-placement.html
    // topology.k8s.aws/zone-id: https://github.com/kubernetes/cloud-provider-aws/blob/3774b6407a490818c9f5cb0ad4fef2ac562fa299/pkg/providers/v1/well_known_labels.go#L19-L27
    @Test
    void registrationLabelsAreTheManagedNodeGroupLabels() {
        Nodegroup group = new Nodegroup();
        group.setNodegroupName("workers");
        group.setCapacityType("SPOT");
        group.setLabels(new LinkedHashMap<>(Map.of("team", "payments", "eks.amazonaws.com/nodegroup", "spoofed",
                "topology.kubernetes.io/zone", "spoofed")));
        group.setLaunchTemplate(Map.of("id", "lt-0f20d6f901007611e", "version", "3"));
        EksNode node = node("i-0123456789abcdef0", 0, "us-east-1b");
        node.setInstanceType("g5.xlarge");
        node.setAmiId("ami-01517c0b8f431a6f8");

        Map<String, String> labels = EksNodeLayout.registrationLabels(group, node, "us-east-1");

        assertEquals("us-east-1", labels.get("topology.kubernetes.io/region"));
        assertEquals("us-east-1b", labels.get("topology.kubernetes.io/zone"));
        assertEquals("use1-az2", labels.get("topology.k8s.aws/zone-id"));
        assertEquals("g5.xlarge", labels.get("node.kubernetes.io/instance-type"));
        assertEquals("g5.xlarge", labels.get("beta.kubernetes.io/instance-type"));
        assertEquals("workers", labels.get("eks.amazonaws.com/nodegroup"));
        assertEquals("ami-01517c0b8f431a6f8", labels.get("eks.amazonaws.com/nodegroup-image"));
        assertEquals("SPOT", labels.get("eks.amazonaws.com/capacityType"));
        assertEquals("lt-0f20d6f901007611e", labels.get("eks.amazonaws.com/sourceLaunchTemplateId"));
        assertEquals("3", labels.get("eks.amazonaws.com/sourceLaunchTemplateVersion"));
        assertEquals("payments", labels.get("team"));
    }

    @Test
    void capacityTypeDefaultsToOnDemandAndLaunchTemplateLabelsNeedATemplate() {
        Nodegroup group = new Nodegroup();
        group.setNodegroupName("workers");
        EksNode node = node("i-0123456789abcdef0", 0, "us-east-1a");
        node.setInstanceType("t3.medium");

        Map<String, String> labels = EksNodeLayout.registrationLabels(group, node, "us-east-1");

        assertEquals("ON_DEMAND", labels.get("eks.amazonaws.com/capacityType"));
        assertFalse(labels.containsKey("eks.amazonaws.com/sourceLaunchTemplateId"));
    }

    // NodeRestriction lets a kubelet set only some kubernetes.io and k8s.io labels on its own Node; the others go on
    // through the API once the node registers.
    // https://kubernetes.io/docs/reference/access-authn-authz/admission-controllers/#noderestriction
    @ParameterizedTest
    @CsvSource({
            "team, true",
            "eks.amazonaws.com/nodegroup, true",
            "topology.k8s.aws/zone-id, true",
            "node.kubernetes.io/instance-type, true",
            "node.kubernetes.io/pool, true",
            "kubelet.kubernetes.io/x, true",
            "topology.kubernetes.io/zone, true",
            "node-role.kubernetes.io/worker, false",
            "example.k8s.io/x, false",
            "node-restriction.kubernetes.io/x, false"
    })
    void kubeletSettableLabelsFollowNodeRestriction(String key, boolean settable) {
        assertEquals(settable, EksNodeLayout.kubeletMaySetLabel(key));
    }

    // Taints: effects NO_SCHEDULE, PREFER_NO_SCHEDULE and NO_EXECUTE in the API.
    // https://docs.aws.amazon.com/eks/latest/userguide/node-taints-managed-node-groups.html
    @Test
    void taintsBecomeRegistrationTaints() {
        Nodegroup group = new Nodegroup();
        group.setTaints(List.of(Map.of("key", "dedicated", "value", "batch", "effect", "NO_SCHEDULE"),
                Map.of("key", "gpu", "effect", "NO_EXECUTE"),
                Map.of("key", "spot", "effect", "PREFER_NO_SCHEDULE")));

        assertEquals(List.of("dedicated=batch:NoSchedule", "gpu:NoExecute", "spot:PreferNoSchedule"),
                EksNodeLayout.registrationTaints(group));
    }

    @Test
    void capRunsTheFirstNodesInNodeGroupCreationOrder() {
        List<List<EksNode>> groups = List.of(
                List.of(node("i-1", 0, "a"), node("i-2", 1, "a"), node("i-3", 2, "a")),
                List.of(node("i-4", 0, "a"), node("i-5", 1, "a")));

        List<String> running = EksNodeLayout.underCap(groups, 4).stream().map(EksNode::getInstanceId).toList();

        assertEquals(List.of("i-1", "i-2", "i-3", "i-4"), running);
        assertEquals(5, EksNodeLayout.underCap(groups, 0).size());
    }

    // GPU instance types report their GPU count from EC2 instance type data.
    // https://docs.aws.amazon.com/ec2/latest/instancetypes/ac.html
    @ParameterizedTest
    @CsvSource({"g5.xlarge, 1", "g5.12xlarge, 4", "g4dn.xlarge, 1", "g6.xlarge, 1", "m5.large, 0", "x9.unknown, 0"})
    void gpusComeFromTheInstanceTypeCatalog(String instanceType, int gpus) {
        assertEquals(gpus, EksNodeLayout.gpus(instanceType, types));
    }

    @Test
    void nodePasswordIsStablePerTokenAndNode() {
        String token = "K10abc::server:join";
        String first = EksNodeLayout.nodePassword(token, node("i-1", 0, "a").getInstanceId());
        assertEquals(first, EksNodeLayout.nodePassword(token, "i-1"));
        assertNotEquals(first, EksNodeLayout.nodePassword(token, "i-2"));
        assertTrue(first.matches("[0-9a-f]{32}"));
    }

    @Test
    void clusterNetworkAddressesSkipTheServerAndTheDynamicRange() {
        assertEquals("10.250.7.10", EksNodeLayout.serverAddress("10.250.7.0/24"));
        assertEquals("10.250.7.128/25", EksNodeLayout.dynamicRange("10.250.7.0/24"));
        assertEquals("10.250.7.20", EksNodeLayout.nextClusterAddress("10.250.7.0/24", List.of()));
        assertEquals("10.250.7.22", EksNodeLayout.nextClusterAddress("10.250.7.0/24", List.of("10.250.7.20", "10.250.7.21")));
        List<String> full = new ArrayList<>();
        for (int host = 20; host < 128; host++) {
            full.add("10.250.7." + host);
        }
        assertEquals(null, EksNodeLayout.nextClusterAddress("10.250.7.0/24", full));
    }

    @ParameterizedTest
    @CsvSource({"rancher/k3s:v1.33.1-k3s1, 1.33", "rancher/k3s:v1.36.0-k3s1, 1.36", "rancher/k3s:latest, ''",
            "myregistry.io/k3s:v1.31.5-k3s1, 1.31"})
    void imageMinorIsReadFromTheK3sTag(String image, String minor) {
        assertEquals(minor.isEmpty() ? null : minor, EksNodeLayout.imageMinor(image));
    }

    private static EksNode node(String id, int index, String az) {
        EksNode node = new EksNode();
        node.setInstanceId(id);
        node.setLaunchIndex(index);
        node.setAvailabilityZone(az);
        return node;
    }
}
