package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.CreateClusterRequest;
import io.floci.aws.services.eks.model.Nodegroup;
import io.floci.aws.services.eks.model.NodegroupStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * CloudFormation provisioning for the EKS control-plane types. {@code AWS::EKS::Cluster} and
 * {@code AWS::EKS::Nodegroup} share one provisioner over {@link EksService} because a nodegroup
 * belongs to a cluster and its delete needs the cluster name, which is stored as a create-time
 * attribute. {@code Ref} returns the cluster name for a Cluster and the nodegroup name for a
 * Nodegroup, matching what the legacy switch published.
 */
@ApplicationScoped
public class EksCfnProvisioner implements CfnResourceProvisioner {

    private static final String CLUSTER = "AWS::EKS::Cluster";
    private static final String NODEGROUP = "AWS::EKS::Nodegroup";

    // EKS gives a node 15 minutes to join; CloudFormation waits for the node group past that.
    private static final long DEFAULT_WAIT_MILLIS = 20 * 60 * 1000L;
    private static final long DEFAULT_POLL_MILLIS = 2000L;

    private final EksService eksService;
    private final long pollMillis;
    private final long waitMillis;

    @Inject
    public EksCfnProvisioner(EksService eksService) {
        this(eksService, DEFAULT_POLL_MILLIS, DEFAULT_WAIT_MILLIS);
    }

    EksCfnProvisioner(EksService eksService, long pollMillis, long waitMillis) {
        this.eksService = eksService;
        this.pollMillis = pollMillis;
        this.waitMillis = waitMillis;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CLUSTER, NODEGROUP);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case CLUSTER -> provisionCluster(r, props, ctx);
            case NODEGROUP -> provisionNodegroup(r, props, ctx);
            default -> throw new IllegalStateException("Unhandled type: " + r.getResourceType());
        }
    }

    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        CreateClusterRequest request = new CreateClusterRequest();
        request.setName(name);
        request.setVersion(ctx.resolveOptional(props, "Version"));
        request.setRoleArn(ctx.resolveOptional(props, "RoleArn"));

        Cluster cluster = eksService.createCluster(request);
        r.setPhysicalId(cluster.getName());
        r.getAttributes().put("Arn", cluster.getArn());
        if (cluster.getEndpoint() != null) {
            r.getAttributes().put("Endpoint", cluster.getEndpoint());
        }
        if (cluster.getCertificateAuthority() != null && cluster.getCertificateAuthority().getData() != null) {
            r.getAttributes().put("CertificateAuthorityData", cluster.getCertificateAuthority().getData());
        }
        if (cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                && cluster.getIdentity().getOidc().getIssuer() != null) {
            r.getAttributes().put("OpenIdConnectIssuerUrl", cluster.getIdentity().getOidc().getIssuer());
        }
    }

    private void provisionNodegroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.resolveOptional(props, "ClusterName");
        Nodegroup request = new Nodegroup();
        String nodegroupName = ctx.resolveOptional(props, "NodegroupName");
        if (nodegroupName == null || nodegroupName.isBlank()) {
            nodegroupName = ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        request.setNodegroupName(nodegroupName);
        request.setNodeRole(ctx.resolveOptional(props, "NodeRole"));
        request.setSubnets(ctx.resolveStringList(props, "Subnets"));

        Nodegroup nodegroup = eksService.createNodeGroup(clusterName, request);
        if (nodegroup.getStatus() == NodegroupStatus.CREATING) {
            awaitActive(clusterName, nodegroupName);
        }
        // Ref (and the readOnly Id) is the composite the registry primary identifier declares,
        // clusterName/nodegroupName, matching AWS. The plain names stay as their own attributes:
        // ClusterName and NodegroupName back delete() and Fn::GetAtt.
        String id = nodegroup.getClusterName() + "/" + nodegroup.getNodegroupName();
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
        r.getAttributes().put("ClusterName", nodegroup.getClusterName());
        r.getAttributes().put("NodegroupName", nodegroup.getNodegroupName());
        if (nodegroup.getNodegroupArn() != null) {
            r.getAttributes().put("Arn", nodegroup.getNodegroupArn());
        }
    }

    /**
     * A node group on a cluster that runs its nodes is CREATING until they are Ready; CloudFormation reports the
     * resource created only once it is ACTIVE, and failed when it is CREATE_FAILED.
     */
    private void awaitActive(String clusterName, String nodegroupName) {
        long deadline = System.currentTimeMillis() + waitMillis;
        while (true) {
            Nodegroup current = eksService.describeNodeGroup(clusterName, nodegroupName);
            NodegroupStatus status = current == null ? NodegroupStatus.ACTIVE : current.getStatus();
            if (status == NodegroupStatus.ACTIVE) {
                return;
            }
            if (status != NodegroupStatus.CREATING) {
                throw new IllegalStateException("EKS node group " + nodegroupName + " of cluster " + clusterName
                        + " is " + status);
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("EKS node group " + nodegroupName + " of cluster " + clusterName
                        + " is still CREATING after " + waitMillis / 1000 + " seconds");
            }
            pause();
        }
    }

    /** A deleted node group is DELETING until its nodes are gone; the cluster it belongs to cannot go before. */
    private void awaitDeleted(String clusterName, String nodegroupName) {
        long deadline = System.currentTimeMillis() + waitMillis;
        while (true) {
            try {
                if (eksService.describeNodeGroup(clusterName, nodegroupName) == null) {
                    return;
                }
            } catch (AwsException e) {
                if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                    return;
                }
                throw e;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("EKS node group " + nodegroupName + " of cluster " + clusterName
                        + " is still DELETING after " + waitMillis / 1000 + " seconds");
            }
            pause();
        }
    }

    private void pause() {
        try {
            Thread.sleep(pollMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an EKS node group", e);
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        switch (resource.getResourceType()) {
            case CLUSTER -> CfnDeletes.safeDelete("EKS cluster", resource.getPhysicalId(),
                    () -> eksService.deleteCluster(resource.getPhysicalId()), "ResourceNotFoundException");
            case NODEGROUP -> {
                // Deleting a nodegroup needs the cluster name and the nodegroup name, both stored as
                // create-time attributes; the physical id is now the composite clusterName/nodegroupName,
                // so it cannot be passed as the nodegroup name.
                String clusterName = resource.getAttributes().get("ClusterName");
                String nodegroupName = resource.getAttributes().get("NodegroupName");
                if (clusterName != null && !clusterName.isBlank()
                        && nodegroupName != null && !nodegroupName.isBlank()) {
                    CfnDeletes.safeDelete("EKS nodegroup", resource.getPhysicalId(),
                            () -> eksService.deleteNodeGroup(clusterName, nodegroupName),
                            "ResourceNotFoundException");
                    awaitDeleted(clusterName, nodegroupName);
                }
            }
            default -> {
                // No other type reaches this provisioner's delete.
            }
        }
    }
}
