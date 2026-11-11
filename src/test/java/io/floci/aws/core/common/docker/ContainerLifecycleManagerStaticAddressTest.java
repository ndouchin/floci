package io.floci.aws.core.common.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.DisconnectFromNetworkCmd;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Networks with a fixed subnet, containers at fixed addresses on them, and containers found by labels. */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerStaticAddressTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
    }

    @Test
    void ensureNetworkWithSubnetCreatesABridgeWhoseDynamicRangeLeavesTheRestForFixedAddresses() {
        InspectNetworkCmd inspect = mock(InspectNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.inspectNetworkCmd()).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("missing"));
        CreateNetworkCmd create = mock(CreateNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.createNetworkCmd()).thenReturn(create);

        String subnet = manager().ensureNetwork("floci-aws-eks-net.demo", "10.250.0.0/24", "10.250.0.128/25",
                Map.of("io.floci.service", "eks"));

        assertEquals("10.250.0.0/24", subnet);
        verify(create).withName("floci-aws-eks-net.demo");
        verify(create).withDriver("bridge");
        ArgumentCaptor<Network.Ipam> ipam = ArgumentCaptor.forClass(Network.Ipam.class);
        verify(create).withIpam(ipam.capture());
        Network.Ipam.Config pool = ipam.getValue().getConfig().getFirst();
        assertEquals("10.250.0.0/24", pool.getSubnet());
        assertEquals("10.250.0.128/25", pool.getIpRange());
        verify(create).withLabels(argThat(labels -> "eks".equals(labels.get("io.floci.service"))
                && "true".equals(labels.get("floci"))));
        verify(create).exec();
    }

    @Test
    void ensureNetworkWithSubnetKeepsAnExistingNetworkAndReportsItsSubnet() {
        InspectNetworkCmd inspect = mock(InspectNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.inspectNetworkCmd()).thenReturn(inspect);
        Network existing = mock(Network.class);
        Network.Ipam ipam = new Network.Ipam().withConfig(new Network.Ipam.Config().withSubnet("10.250.3.0/24"));
        when(existing.getIpam()).thenReturn(ipam);
        when(inspect.exec()).thenReturn(existing);

        String subnet = manager().ensureNetwork("floci-aws-eks-net.demo", "10.250.0.0/24", "10.250.0.128/25", Map.of());

        assertEquals("10.250.3.0/24", subnet);
        verify(dockerClient, never()).createNetworkCmd();
        assertEquals(Optional.of("10.250.3.0/24"), manager().networkSubnet("floci-aws-eks-net.demo"));
    }

    @Test
    void networkSubnetIsEmptyForANetworkDockerDoesNotHave() {
        InspectNetworkCmd inspect = mock(InspectNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.inspectNetworkCmd()).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("missing"));

        assertEquals(Optional.empty(), manager().networkSubnet("floci-aws-eks-net.gone"));
    }

    @Test
    void connectToNetworkAtAnAddressAsksDockerForThatAddress() throws Exception {
        ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.connectToNetworkCmd()).thenReturn(connect);

        manager().connectToNetwork("container-id", "floci-aws-eks-net.demo", "10.250.0.10");

        verify(connect).withContainerId("container-id");
        verify(connect).withNetworkId("floci-aws-eks-net.demo");
        ArgumentCaptor<ContainerNetwork> endpoint = ArgumentCaptor.forClass(ContainerNetwork.class);
        verify(connect).withContainerNetwork(endpoint.capture());
        String serialized = new ObjectMapper().writeValueAsString(endpoint.getValue());
        assertTrue(serialized.contains("\"IPv4Address\":\"10.250.0.10\""), serialized);
        verify(connect).exec();
    }

    @Test
    void reconnectAtAnAddressMovesACreatedContainersEndpointToThatAddress() throws Exception {
        DisconnectFromNetworkCmd disconnect = mock(DisconnectFromNetworkCmd.class, RETURNS_SELF);
        ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.disconnectFromNetworkCmd()).thenReturn(disconnect);
        when(dockerClient.connectToNetworkCmd()).thenReturn(connect);

        manager().reconnectToNetwork("container-id", "floci-aws-eks-net.demo", "10.250.0.21");

        InOrder order = inOrder(disconnect, connect);
        order.verify(disconnect).exec();
        order.verify(connect).exec();
        verify(disconnect).withNetworkId("floci-aws-eks-net.demo");
        ArgumentCaptor<ContainerNetwork> endpoint = ArgumentCaptor.forClass(ContainerNetwork.class);
        verify(connect).withContainerNetwork(endpoint.capture());
        assertTrue(new ObjectMapper().writeValueAsString(endpoint.getValue())
                .contains("\"IPv4Address\":\"10.250.0.21\""));
    }

    @Test
    void listByLabelsAsksDockerForEveryContainerCarryingAllTheLabels() {
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(list);
        Container agent = mock(Container.class);
        when(list.exec()).thenReturn(List.of(agent));

        List<Container> found = manager().listByLabels(Map.of("io.floci.eks.cluster", "demo",
                "io.floci.eks.role", "node"));

        assertEquals(List.of(agent), found);
        verify(list).withShowAll(true);
        verify(list).withLabelFilter(Map.of("io.floci.eks.cluster", "demo", "io.floci.eks.role", "node"));
    }

    @Test
    void listByLabelsIsEmptyWhenDockerCannotAnswer() {
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenThrow(new RuntimeException("daemon gone"));

        assertEquals(List.of(), manager().listByLabels(Map.of("io.floci.eks.cluster", "demo")));
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config);
    }
}
