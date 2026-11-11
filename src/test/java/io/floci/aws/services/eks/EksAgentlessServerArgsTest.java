package io.floci.aws.services.eks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The k3s server of a multi-node cluster runs the control plane only; the kubelets run on the agents. */
class EksAgentlessServerArgsTest {

    @Test
    void serverRunsNoKubeletAndAdvertisesItsClusterNetworkAddress() {
        List<String> args = EksClusterManager.buildAgentlessServerArgs(false, false, "10.100.0.0/16",
                "10.42.0.0/16", "floci-aws-eks-demo", "10.250.0.10", "emulated");

        assertEquals("server", args.getFirst());
        assertTrue(args.contains("--disable-agent"));
        assertTrue(args.contains("--egress-selector-mode=cluster"));
        assertTrue(args.contains("--advertise-address=10.250.0.10"));
        assertTrue(args.contains("--tls-san=floci-aws-eks-demo"));
        assertTrue(args.contains("--tls-san=localhost"));
        assertTrue(args.contains("--disable=traefik"));
        assertTrue(args.contains("--disable=local-storage"));
        assertTrue(args.contains("--service-cidr=10.100.0.0/16"));
        assertTrue(args.contains("--cluster-cidr=10.42.0.0/16"));
        assertTrue(args.stream().noneMatch(arg -> arg.startsWith("--kubelet-arg") || arg.startsWith("--node-")),
                args.toString());
    }

    // ExtendedResourceToleration and NodeRestriction are among the admission controllers EKS enables.
    // https://docs.aws.amazon.com/eks/latest/userguide/platform-versions.html
    @Test
    void serverEnablesTheAdmissionPluginsEksEnables() {
        List<String> args = EksClusterManager.buildAgentlessServerArgs(false, false, null, null,
                "floci-aws-eks-demo", "10.250.0.10", "emulated");

        assertTrue(args.contains("--kube-apiserver-arg=enable-admission-plugins=NodeRestriction,ExtendedResourceToleration"));
    }

    @Test
    void servicelbRunsOnlyInServicelbMode() {
        assertTrue(EksClusterManager.buildAgentlessServerArgs(false, false, null, null, "s", "10.250.0.10", "emulated")
                .contains("--disable=servicelb"));
        assertTrue(EksClusterManager.buildAgentlessServerArgs(false, false, null, null, "s", "10.250.0.10", "off")
                .contains("--disable=servicelb"));
        assertFalse(EksClusterManager.buildAgentlessServerArgs(false, false, null, null, "s", "10.250.0.10", "servicelb")
                .contains("--disable=servicelb"));
    }

    @Test
    void cniDisabledKeepsFlannelAndKubeProxyOffOnTheServer() {
        List<String> args = EksClusterManager.buildAgentlessServerArgs(true, true, null, null, "s", "10.250.0.10",
                "emulated");

        assertTrue(args.contains("--flannel-backend=none"));
        assertTrue(args.contains("--disable-kube-proxy"));
        assertFalse(args.contains("--disable=local-storage"));
    }

    @Test
    void callerKubeletArgsGoToTheAgentsAndTheRestToTheServer() {
        List<String> caller = List.of("--kubelet-arg=image-gc-high-threshold=80",
                "--kube-apiserver-arg=default-watch-cache-size=200", "--kube-scheduler-arg=v=2");

        assertEquals(List.of("--kube-apiserver-arg=default-watch-cache-size=200", "--kube-scheduler-arg=v=2"),
                EksClusterManager.serverCallerArgs(caller));
        assertEquals(List.of("--kubelet-arg=image-gc-high-threshold=80"), EksClusterManager.agentCallerArgs(caller));
    }
}
