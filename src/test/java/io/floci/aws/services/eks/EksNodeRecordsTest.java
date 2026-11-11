package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.EksNode;
import io.floci.aws.services.eks.model.Nodegroup;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The node and network state a multi-node cluster keeps on its records, and what the API leaves out of it. */
class EksNodeRecordsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void nodegroupKeepsItsNodesAndLaunchCounterThroughStorage() throws Exception {
        Nodegroup group = nodegroupWithNodes();

        Nodegroup restored = MAPPER.readValue(MAPPER.writeValueAsString(group), Nodegroup.class);

        assertEquals(3, restored.getLaunchCounter());
        assertEquals(1, restored.getNodes().size());
        EksNode node = restored.getNodes().getFirst();
        assertEquals("i-0123456789abcdef0", node.getInstanceId());
        assertEquals("ip-10-0-1-23.ec2.internal", node.getNodeName());
        assertEquals("10.0.1.23", node.getPrivateIp());
        assertEquals("subnet-a", node.getSubnetId());
        assertEquals("us-east-1a", node.getAvailabilityZone());
        assertEquals("1.33", node.getVersion());
        assertEquals(2, node.getLaunchIndex());
        assertTrue(node.isRunning());
        assertTrue(node.isVpcAddress());
        assertEquals("floci-aws-eks-node.demo.i-0123456789abcdef0", node.getContainerName());
        assertEquals("1.34", restored.getPendingVersion());
        assertEquals("upd-1", restored.getPendingUpdateId());
    }

    @Test
    void clusterKeepsItsNodeNetworkAndJoinTokenThroughStorage() throws Exception {
        Cluster cluster = clusterWithNetwork();

        Cluster restored = MAPPER.readValue(MAPPER.writeValueAsString(cluster), Cluster.class);

        assertEquals("agentless", restored.getTopology());
        assertEquals("K10abc::server:join", restored.getJoinToken());
        assertEquals("floci-aws-eks-net.demo", restored.getNodeNetwork());
        assertEquals("10.250.0.0/24", restored.getNodeSubnet());
        assertEquals("10.250.0.10", restored.getServerAddress());
        assertEquals("emulated", restored.getLoadBalancerMode());
        assertTrue(restored.isAgentless());
    }

    @Test
    void aClusterStoredByRoundOneIsSingleNode() throws Exception {
        Cluster restored = MAPPER.readValue("{\"name\":\"legacy\",\"status\":\"ACTIVE\"}", Cluster.class);

        assertNull(restored.getTopology());
        assertFalse(restored.isAgentless());
    }

    @Test
    void nodegroupResponseLeavesTheNodesOut() throws Exception {
        Nodegroup group = nodegroupWithNodes();

        String json = MAPPER.writeValueAsString(EksController.toNodegroupResponse(group));

        assertFalse(json.contains("\"nodes\""), json);
        assertFalse(json.contains("launchCounter"), json);
        assertFalse(json.contains("pending"), json);
        assertTrue(json.contains("\"nodegroupName\":\"workers\""), json);
        assertEquals(1, group.getNodes().size());
    }

    @Test
    void clusterResponseLeavesTheNodeNetworkOut() throws Exception {
        String json = MAPPER.writeValueAsString(EksController.toClusterResponse(clusterWithNetwork()));

        for (String field : List.of("topology", "joinToken", "nodeNetwork", "nodeSubnet", "serverAddress",
                "loadBalancerMode")) {
            assertFalse(json.contains(field), field + " in " + json);
        }
        assertTrue(json.contains("\"name\":\"demo\""), json);
    }

    private static Nodegroup nodegroupWithNodes() {
        Nodegroup group = new Nodegroup();
        group.setNodegroupName("workers");
        group.setLabels(Map.of("team", "payments"));
        EksNode node = new EksNode();
        node.setInstanceId("i-0123456789abcdef0");
        node.setNodeName("ip-10-0-1-23.ec2.internal");
        node.setPrivateIp("10.0.1.23");
        node.setSubnetId("subnet-a");
        node.setAvailabilityZone("us-east-1a");
        node.setVersion("1.33");
        node.setLaunchIndex(2);
        node.setRunning(true);
        node.setVpcAddress(true);
        node.setContainerName("floci-aws-eks-node.demo.i-0123456789abcdef0");
        node.setLaunchedAt(Instant.ofEpochSecond(1_700_000_000L));
        group.setNodes(List.of(node));
        group.setLaunchCounter(3);
        group.setPendingVersion("1.34");
        group.setPendingUpdateId("upd-1");
        return group;
    }

    private static Cluster clusterWithNetwork() {
        Cluster cluster = new Cluster();
        cluster.setName("demo");
        cluster.setTopology("agentless");
        cluster.setJoinToken("K10abc::server:join");
        cluster.setNodeNetwork("floci-aws-eks-net.demo");
        cluster.setNodeSubnet("10.250.0.0/24");
        cluster.setServerAddress("10.250.0.10");
        cluster.setLoadBalancerMode("emulated");
        return cluster;
    }
}
