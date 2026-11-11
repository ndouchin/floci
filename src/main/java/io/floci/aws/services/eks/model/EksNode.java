package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * One node of a managed node group: the EC2 instance EKS's Auto Scaling group would launch, run as a k3s agent
 * container. Internal persisted state of {@link Nodegroup}, never part of an API response.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class EksNode {

    private String instanceId;
    private String nodeName;
    private String privateIp;
    private String clusterIp;
    private String subnetId;
    private String availabilityZone;
    private String availabilityZoneId;
    private String instanceType;
    private String version;
    private String image;
    private String amiId;
    private int launchIndex;
    private boolean vpcAddress;
    private String vpcId;
    private String replaces;
    private boolean running;
    private boolean ready;
    private String containerName;
    private String volumeName;

    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant launchedAt;

    public EksNode() {}

    public EksNode copy() {
        EksNode copy = new EksNode();
        copy.instanceId = instanceId;
        copy.nodeName = nodeName;
        copy.privateIp = privateIp;
        copy.clusterIp = clusterIp;
        copy.subnetId = subnetId;
        copy.availabilityZone = availabilityZone;
        copy.availabilityZoneId = availabilityZoneId;
        copy.instanceType = instanceType;
        copy.version = version;
        copy.image = image;
        copy.amiId = amiId;
        copy.launchIndex = launchIndex;
        copy.vpcAddress = vpcAddress;
        copy.vpcId = vpcId;
        copy.replaces = replaces;
        copy.running = running;
        copy.ready = ready;
        copy.containerName = containerName;
        copy.volumeName = volumeName;
        copy.launchedAt = launchedAt;
        return copy;
    }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }

    public String getPrivateIp() { return privateIp; }
    public void setPrivateIp(String privateIp) { this.privateIp = privateIp; }

    /** The node's fixed address on the cluster network; its private IP too when no VPC address was leased. */
    public String getClusterIp() { return clusterIp; }
    public void setClusterIp(String clusterIp) { this.clusterIp = clusterIp; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getAvailabilityZoneId() { return availabilityZoneId; }
    public void setAvailabilityZoneId(String availabilityZoneId) { this.availabilityZoneId = availabilityZoneId; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getAmiId() { return amiId; }
    public void setAmiId(String amiId) { this.amiId = amiId; }

    public int getLaunchIndex() { return launchIndex; }
    public void setLaunchIndex(int launchIndex) { this.launchIndex = launchIndex; }

    public boolean isVpcAddress() { return vpcAddress; }
    public void setVpcAddress(boolean vpcAddress) { this.vpcAddress = vpcAddress; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getReplaces() { return replaces; }
    public void setReplaces(String replaces) { this.replaces = replaces; }

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public boolean isReady() { return ready; }
    public void setReady(boolean ready) { this.ready = ready; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    public String getVolumeName() { return volumeName; }
    public void setVolumeName(String volumeName) { this.volumeName = volumeName; }

    public Instant getLaunchedAt() { return launchedAt; }
    public void setLaunchedAt(Instant launchedAt) { this.launchedAt = launchedAt; }
}
