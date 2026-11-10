package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs EKS clusters as real k3s containers for the classes that drive a cluster through its
 * lifecycle with the EKS API: node group labels and taints on the running node, cluster and node
 * group updates, add-ons that change what runs in the cluster, and deletion rules.
 *
 * <p>It is a profile of its own rather than {@link RealEksProfile} so the CI partitioner places
 * these classes, each of which boots at least one cluster, on a shard apart from the other
 * real-cluster classes.
 */
public class EksRuntimeDockerProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.eks.mock", "false");
    }
}
