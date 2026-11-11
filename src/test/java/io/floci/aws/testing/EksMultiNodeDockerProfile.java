package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Real k3s clusters whose managed nodes run as k3s agents on the VPC's Docker network: EKS and EC2 unmocked, with
 * Docker-backed VPC networks so nodes take addresses from their subnets. A profile of its own, so the CI partitioner
 * places these classes, each of which boots a server and several agents, on a shard apart from the other EKS
 * Docker classes; its resource namespace keeps the networks it makes apart from any other Floci on the daemon.
 */
public class EksMultiNodeDockerProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.eks.mock", "false",
                "floci.services.ec2.mock", "false",
                "floci.services.ec2.vpc-networks.enabled", "true",
                "floci.network.security-group-enforcement.enabled", "false",
                "floci.services.eks.max-nodes-per-cluster", "5",
                "floci.docker.resource-namespace", "eks-multinode-it");
    }
}
