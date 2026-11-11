package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EKS managed node group. Doubles as the {@code CreateNodegroup} request body (the input
 * members are a subset) and the wire response. Recursive sub-structures
 * (remoteAccess, taints, launchTemplate, updateConfig, nodeRepairConfig, warmPoolConfig,
 * resources, health) round-trip as generic JSON so we don't over-model what the management
 * plane just echoes.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Nodegroup {

    @JsonProperty("nodegroupName")
    private String nodegroupName;

    @JsonProperty("nodegroupArn")
    private String nodegroupArn;

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("version")
    private String version;

    @JsonProperty("releaseVersion")
    private String releaseVersion;

    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;

    @JsonProperty("modifiedAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant modifiedAt;

    @JsonProperty("status")
    private NodegroupStatus status;

    @JsonProperty("capacityType")
    private String capacityType;

    @JsonProperty("scalingConfig")
    private NodegroupScalingConfig scalingConfig;

    @JsonProperty("instanceTypes")
    private List<String> instanceTypes;

    @JsonProperty("subnets")
    private List<String> subnets;

    @JsonProperty("remoteAccess")
    private Object remoteAccess;

    @JsonProperty("amiType")
    private String amiType;

    @JsonProperty("nodeRole")
    private String nodeRole;

    @JsonProperty("labels")
    private Map<String, String> labels;

    @JsonProperty("taints")
    private List<Object> taints;

    @JsonProperty("resources")
    private Object resources;

    @JsonProperty("diskSize")
    private Integer diskSize;

    @JsonProperty("health")
    private Object health;

    @JsonProperty("updateConfig")
    private Object updateConfig;

    @JsonProperty("nodeRepairConfig")
    private Object nodeRepairConfig;

    @JsonProperty("launchTemplate")
    private Object launchTemplate;

    @JsonProperty("warmPoolConfig")
    private Object warmPoolConfig;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("clientRequestToken")
    @JsonIgnore
    private String clientRequestToken;

    @JsonIgnore
    private String accountId;

    /**
     * The node group's nodes, one k3s agent each while under the node cap. Internal persisted state, left out
     * of API responses by {@code EksController.toNodegroupResponse}.
     */
    @JsonProperty("nodes")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<EksNode> nodes;

    /** How many nodes the node group has launched: the next node's launch index, so a replacement gets a new identity. */
    @JsonProperty("launchCounter")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int launchCounter;

    /** The update the node reconciler finishes once the node group's nodes match it. */
    @JsonProperty("pendingUpdateId")
    private String pendingUpdateId;

    /** The Kubernetes version a version update replaces the nodes with. */
    @JsonProperty("pendingVersion")
    private String pendingVersion;

    @JsonProperty("pendingReleaseVersion")
    private String pendingReleaseVersion;

    @JsonProperty("pendingLaunchTemplate")
    private Object pendingLaunchTemplate;

    @JsonProperty("pendingForce")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean pendingForce;

    @JsonProperty("pendingSince")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant pendingSince;

    public Nodegroup() {}

    /** A copy without the internal node state, for an API response. */
    public Nodegroup withoutInternalState() {
        Nodegroup copy = new Nodegroup();
        copy.nodegroupName = nodegroupName;
        copy.nodegroupArn = nodegroupArn;
        copy.clusterName = clusterName;
        copy.version = version;
        copy.releaseVersion = releaseVersion;
        copy.createdAt = createdAt;
        copy.modifiedAt = modifiedAt;
        copy.status = status;
        copy.capacityType = capacityType;
        copy.scalingConfig = scalingConfig;
        copy.instanceTypes = instanceTypes;
        copy.subnets = subnets;
        copy.remoteAccess = remoteAccess;
        copy.amiType = amiType;
        copy.nodeRole = nodeRole;
        copy.labels = labels;
        copy.taints = taints;
        copy.resources = resources;
        copy.diskSize = diskSize;
        copy.health = health;
        copy.updateConfig = updateConfig;
        copy.nodeRepairConfig = nodeRepairConfig;
        copy.launchTemplate = launchTemplate;
        copy.warmPoolConfig = warmPoolConfig;
        copy.tags = tags;
        copy.clientRequestToken = clientRequestToken;
        copy.accountId = accountId;
        return copy;
    }

    public boolean hasInternalState() {
        return (nodes != null && !nodes.isEmpty()) || launchCounter != 0 || pendingUpdateId != null
                || pendingVersion != null || pendingReleaseVersion != null || pendingLaunchTemplate != null
                || pendingForce || pendingSince != null;
    }

    public List<EksNode> getNodes() {
        if (nodes == null) {
            nodes = new ArrayList<>();
        } else if (!(nodes instanceof ArrayList)) {
            nodes = new ArrayList<>(nodes);
        }
        return nodes;
    }
    public void setNodes(List<EksNode> nodes) { this.nodes = nodes == null ? null : new ArrayList<>(nodes); }

    public int getLaunchCounter() { return launchCounter; }
    public void setLaunchCounter(int launchCounter) { this.launchCounter = launchCounter; }

    public String getPendingUpdateId() { return pendingUpdateId; }
    public void setPendingUpdateId(String pendingUpdateId) { this.pendingUpdateId = pendingUpdateId; }

    public String getPendingVersion() { return pendingVersion; }
    public void setPendingVersion(String pendingVersion) { this.pendingVersion = pendingVersion; }

    public String getPendingReleaseVersion() { return pendingReleaseVersion; }
    public void setPendingReleaseVersion(String pendingReleaseVersion) { this.pendingReleaseVersion = pendingReleaseVersion; }

    public Object getPendingLaunchTemplate() { return pendingLaunchTemplate; }
    public void setPendingLaunchTemplate(Object pendingLaunchTemplate) { this.pendingLaunchTemplate = pendingLaunchTemplate; }

    public boolean isPendingForce() { return pendingForce; }
    public void setPendingForce(boolean pendingForce) { this.pendingForce = pendingForce; }

    public Instant getPendingSince() { return pendingSince; }
    public void setPendingSince(Instant pendingSince) { this.pendingSince = pendingSince; }

    public String getNodegroupName() { return nodegroupName; }
    public void setNodegroupName(String nodegroupName) { this.nodegroupName = nodegroupName; }

    public String getNodegroupArn() { return nodegroupArn; }
    public void setNodegroupArn(String nodegroupArn) { this.nodegroupArn = nodegroupArn; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getReleaseVersion() { return releaseVersion; }
    public void setReleaseVersion(String releaseVersion) { this.releaseVersion = releaseVersion; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }

    public NodegroupStatus getStatus() { return status; }
    public void setStatus(NodegroupStatus status) { this.status = status; }

    public String getCapacityType() { return capacityType; }
    public void setCapacityType(String capacityType) { this.capacityType = capacityType; }

    public NodegroupScalingConfig getScalingConfig() { return scalingConfig; }
    public void setScalingConfig(NodegroupScalingConfig scalingConfig) { this.scalingConfig = scalingConfig; }

    public List<String> getInstanceTypes() { return instanceTypes; }
    public void setInstanceTypes(List<String> instanceTypes) { this.instanceTypes = instanceTypes; }

    public List<String> getSubnets() { return subnets; }
    public void setSubnets(List<String> subnets) { this.subnets = subnets; }

    public Object getRemoteAccess() { return remoteAccess; }
    public void setRemoteAccess(Object remoteAccess) { this.remoteAccess = remoteAccess; }

    public String getAmiType() { return amiType; }
    public void setAmiType(String amiType) { this.amiType = amiType; }

    public String getNodeRole() { return nodeRole; }
    public void setNodeRole(String nodeRole) { this.nodeRole = nodeRole; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public List<Object> getTaints() { return taints; }
    public void setTaints(List<Object> taints) { this.taints = taints; }

    public Object getResources() { return resources; }
    public void setResources(Object resources) { this.resources = resources; }

    public Integer getDiskSize() { return diskSize; }
    public void setDiskSize(Integer diskSize) { this.diskSize = diskSize; }

    public Object getHealth() { return health; }
    public void setHealth(Object health) { this.health = health; }

    public Object getUpdateConfig() { return updateConfig; }
    public void setUpdateConfig(Object updateConfig) { this.updateConfig = updateConfig; }

    public Object getNodeRepairConfig() { return nodeRepairConfig; }
    public void setNodeRepairConfig(Object nodeRepairConfig) { this.nodeRepairConfig = nodeRepairConfig; }

    public Object getLaunchTemplate() { return launchTemplate; }
    public void setLaunchTemplate(Object launchTemplate) { this.launchTemplate = launchTemplate; }

    public Object getWarmPoolConfig() { return warmPoolConfig; }
    public void setWarmPoolConfig(Object warmPoolConfig) { this.warmPoolConfig = warmPoolConfig; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
}
