package io.floci.aws.services.eks;

import io.floci.aws.services.eks.EksNodeReconciler.NodeLaunchException;
import io.floci.aws.services.eks.EksNodeReconciler.NodeState;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.eks.model.EksNode;
import io.floci.aws.services.eks.model.Nodegroup;
import io.floci.aws.services.eks.model.NodegroupScalingConfig;
import io.floci.aws.services.eks.model.NodegroupStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reconciler's rules, each from the multi-node map's section 3.8, against a fake runtime and store. */
class EksNodeReconcilerTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER = "demo";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
    private FakeStore store;
    private FakeRuntime runtime;
    private EksNodeReconciler reconciler;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        runtime = new FakeRuntime();
        reconciler = reconciler(5);
    }

    @AfterEach
    void tearDown() {
        reconciler.shutdown();
    }

    private EksNodeReconciler reconciler(int cap) {
        return new EksNodeReconciler(runtime, store, new EksNodeReconciler.Settings(cap, 900, 60), clock,
                Executors.newSingleThreadExecutor());
    }

    @Test
    void creatingNodeGroupLaunchesItsNodesAndTurnsActiveOnceTheyAreReady() {
        Nodegroup group = store.add("workers", 2, NodegroupStatus.CREATING);
        runtime.readyAfterStart = false;

        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(2, group.getNodes().size());
        assertEquals(NodegroupStatus.CREATING, group.getStatus());
        assertEquals(2, runtime.started.size());
        assertEquals(2, group.getLaunchCounter());

        runtime.readyAfterStart = true;
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(NodegroupStatus.ACTIVE, group.getStatus());
        assertTrue(group.getNodes().stream().allMatch(EksNode::isReady));
        assertEquals(2, runtime.started.size(), "an adopted agent is not started again");
    }

    @Test
    void nodeIsReadyOnlyWhenItsNodeIsReadyInitializedAndRunsItsVersion() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.CREATING);
        runtime.stateOverride = node -> new NodeState(true, true, "v1.33.1+k3s1");
        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));

        runtime.stateOverride = node -> new NodeState(true, false, "v1.32.2+k3s1");
        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));

        runtime.stateOverride = node -> new NodeState(false, false, "v1.33.1+k3s1");
        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(NodegroupStatus.CREATING, group.getStatus());

        runtime.stateOverride = null;
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(NodegroupStatus.ACTIVE, group.getStatus());
    }

    @Test
    void nodeAddedWhileAPassRunsIsLeftForTheNextPass() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.ACTIVE);
        runtime.onEnsure = node -> {
            if (group.getNodes().size() == 1) {
                group.getScalingConfig().setDesiredSize(2);
                group.getNodes().add(launched("i-added", 9));
            }
        };

        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));
        runtime.onEnsure = null;
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
        assertTrue(runtime.started.contains("i-added"));
    }

    @Test
    void passThatStartedBeforeTheLatestChangeDoesNotSettleTheCluster() {
        store.add("workers", 1, NodegroupStatus.ACTIVE);
        runtime.onEnsure = node -> reconciler.request(ACCOUNT, CLUSTER);

        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));
        runtime.onEnsure = null;
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
    }

    @Test
    void noNodeWorkWhileTheServerIsBeingReplaced() {
        store.add("workers", 1, NodegroupStatus.CREATING);
        reconciler.replacingControlPlane(ACCOUNT, CLUSTER, true);

        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));
        assertTrue(runtime.started.isEmpty());
    }

    @Test
    void clusterDeletedWhileAPassRunsRemovesTheAgentsThePassStarted() {
        store.add("workers", 2, NodegroupStatus.CREATING);
        runtime.onEnsure = node -> store.cluster = null;

        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(runtime.started, runtime.terminated);
    }

    @Test
    void everyPassRemovesTheAgentsNoNodeHas() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.ACTIVE);

        reconciler.pass(ACCOUNT, CLUSTER);

        assertEquals(Set.of(group.getNodes().getFirst().getInstanceId()), runtime.lastExpectedAgents);
        assertEquals(Set.of(group.getNodes().getFirst().getNodeName()), runtime.lastExpectedNodeNames);
    }

    @Test
    void nodesPastTheCapKeepTheirRecordWithoutAnAgent() {
        Nodegroup first = store.add("first", 4, NodegroupStatus.CREATING);
        Nodegroup second = store.add("second", 2, NodegroupStatus.CREATING);

        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));

        assertEquals(4, first.getNodes().size());
        assertEquals(2, second.getNodes().size());
        assertEquals(5, runtime.started.size());
        assertTrue(second.getNodes().get(0).isRunning());
        assertFalse(second.getNodes().get(1).isRunning());
        assertEquals(NodegroupStatus.ACTIVE, second.getStatus());
    }

    @Test
    void scalingUpLaunchesNewIdentitiesAndScalingDownTerminatesTheNewest() {
        Nodegroup group = store.add("workers", 2, NodegroupStatus.ACTIVE);
        reconciler.pass(ACCOUNT, CLUSTER);
        List<String> before = ids(group);

        group.getScalingConfig().setDesiredSize(3);
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(3, group.getNodes().size());
        String added = ids(group).stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();

        group.getScalingConfig().setDesiredSize(1);
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(1, group.getNodes().size());
        assertTrue(runtime.terminated.contains(added));
        assertEquals(before.getFirst(), group.getNodes().getFirst().getInstanceId());
    }

    @Test
    void deletingNodeGroupIsDeletedOnceItsAgentsAreGone() {
        Nodegroup group = store.add("workers", 2, NodegroupStatus.ACTIVE);
        reconciler.pass(ACCOUNT, CLUSTER);
        List<String> nodes = ids(group);

        group.setStatus(NodegroupStatus.DELETING);
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));

        assertTrue(store.groups.isEmpty());
        assertTrue(runtime.terminated.containsAll(nodes));
    }

    @Test
    void nodesNotReadyInTimeFailTheNodeGroupWithNodeCreationFailure() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.CREATING);
        runtime.readyAfterStart = false;
        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));

        clock.advanceSeconds(901);
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));

        assertEquals(NodegroupStatus.CREATE_FAILED, group.getStatus());
        assertEquals("NodeCreationFailure", issueCode(group));
    }

    @Test
    void agentThatCannotBeCreatedFailsTheNodeGroupWithAsgInstanceLaunchFailures() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.CREATING);
        runtime.failure = new IllegalStateException("no space left on device");
        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));

        clock.advanceSeconds(901);
        reconciler.pass(ACCOUNT, CLUSTER);

        assertEquals(NodegroupStatus.CREATE_FAILED, group.getStatus());
        assertEquals("AsgInstanceLaunchFailures", issueCode(group));
    }

    @Test
    void failingUserDataFailsTheNodeGroupAtOnce() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.CREATING);
        runtime.failure = new NodeLaunchException("NodeCreationFailure", "user data exited with 3", true);

        reconciler.pass(ACCOUNT, CLUSTER);

        assertEquals(NodegroupStatus.CREATE_FAILED, group.getStatus());
        assertEquals("NodeCreationFailure", issueCode(group));
    }

    @Test
    void activeNodeGroupWhoseNodeCannotBeRelaunchedIsDegradedUntilItIs() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.ACTIVE);
        reconciler.pass(ACCOUNT, CLUSTER);
        runtime.failure = new IllegalStateException("image gone");

        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(NodegroupStatus.DEGRADED, group.getStatus());

        runtime.failure = null;
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(NodegroupStatus.ACTIVE, group.getStatus());
    }

    @Test
    void configUpdateFinishesOnceTheNodeGroupHasItsNewSize() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.ACTIVE);
        reconciler.pass(ACCOUNT, CLUSTER);
        group.setStatus(NodegroupStatus.UPDATING);
        group.setPendingUpdateId("upd-scale");
        group.getScalingConfig().setDesiredSize(2);
        runtime.readyAfterStart = false;

        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(NodegroupStatus.UPDATING, group.getStatus());

        runtime.readyAfterStart = true;
        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
        assertEquals(NodegroupStatus.ACTIVE, group.getStatus());
        assertEquals(List.of("upd-scale:ok"), store.finished);
    }

    @Test
    void versionUpdateReplacesNodesOneBatchAtATimeWithNewIdentities() {
        Nodegroup group = store.add("workers", 2, NodegroupStatus.ACTIVE);
        reconciler.pass(ACCOUNT, CLUSTER);
        List<String> before = ids(group);
        startVersionUpdate(group, "1.34", false);

        reconciler.pass(ACCOUNT, CLUSTER);
        assertEquals(3, group.getNodes().size(), "one replacement surges first");
        assertTrue(runtime.drained.isEmpty(), "nothing drains before the replacement is Ready");

        reconciler.pass(ACCOUNT, CLUSTER);
        assertEquals(List.of(before.get(0)), runtime.drained);

        for (int pass = 0; pass < 6 && group.getStatus() == NodegroupStatus.UPDATING; pass++) {
            reconciler.pass(ACCOUNT, CLUSTER);
        }

        assertEquals(NodegroupStatus.ACTIVE, group.getStatus());
        assertEquals("1.34", group.getVersion());
        assertTrue(group.getReleaseVersion().startsWith("1.34."), group.getReleaseVersion());
        assertEquals(2, group.getNodes().size());
        assertTrue(group.getNodes().stream().allMatch(node -> "1.34".equals(node.getVersion())));
        assertTrue(group.getNodes().stream().noneMatch(node -> before.contains(node.getInstanceId())));
        assertEquals(before, runtime.drained);
        assertEquals(List.of("upd-version:ok"), store.finished);
    }

    @Test
    void podsThatCannotBeEvictedFailTheVersionUpdate() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.ACTIVE);
        reconciler.pass(ACCOUNT, CLUSTER);
        String old = group.getNodes().getFirst().getInstanceId();
        startVersionUpdate(group, "1.34", false);
        runtime.drainSucceeds = false;

        for (int pass = 0; pass < 4 && group.getStatus() == NodegroupStatus.UPDATING; pass++) {
            reconciler.pass(ACCOUNT, CLUSTER);
        }

        assertEquals(List.of("upd-version:PodEvictionFailure"), store.finished);
        assertEquals("PodEvictionFailure", issueCode(group));
        assertEquals("1.33", group.getVersion());
        assertTrue(runtime.uncordoned.contains(old));
        reconciler.pass(ACCOUNT, CLUSTER);
        assertEquals(List.of(old), ids(group), "the surplus replacement goes");
    }

    @Test
    void forcedVersionUpdateTerminatesNodesWhosePodsCannotBeEvicted() {
        Nodegroup group = store.add("workers", 1, NodegroupStatus.ACTIVE);
        reconciler.pass(ACCOUNT, CLUSTER);
        startVersionUpdate(group, "1.34", true);
        runtime.drainSucceeds = false;

        for (int pass = 0; pass < 4 && group.getStatus() == NodegroupStatus.UPDATING; pass++) {
            reconciler.pass(ACCOUNT, CLUSTER);
        }

        assertEquals(List.of("upd-version:ok"), store.finished);
        assertEquals("1.34", group.getVersion());
    }

    @Test
    void versionUpdateWithoutAFreeSlotTerminatesBeforeItLaunches() {
        reconciler = reconciler(1);
        Nodegroup group = store.add("workers", 1, NodegroupStatus.ACTIVE);
        reconciler.pass(ACCOUNT, CLUSTER);
        String old = group.getNodes().getFirst().getInstanceId();
        startVersionUpdate(group, "1.34", false);

        reconciler.pass(ACCOUNT, CLUSTER);
        assertEquals(List.of(old), runtime.drained);
        assertEquals(0, group.getNodes().size());

        for (int pass = 0; pass < 4 && group.getStatus() == NodegroupStatus.UPDATING; pass++) {
            reconciler.pass(ACCOUNT, CLUSTER);
        }
        assertEquals(NodegroupStatus.ACTIVE, group.getStatus());
        assertEquals(1, group.getNodes().size());
        assertNotEquals(old, group.getNodes().getFirst().getInstanceId());
    }

    @Test
    void clusterNotActiveYetIsRetried() {
        store.cluster.setStatus(ClusterStatus.CREATING);
        store.add("workers", 1, NodegroupStatus.CREATING);

        assertFalse(reconciler.pass(ACCOUNT, CLUSTER));
        assertTrue(runtime.started.isEmpty());
    }

    @Test
    void singleNodeClusterFromRoundOneIsLeftAlone() {
        store.cluster.setTopology(null);
        Nodegroup group = store.add("workers", 1, NodegroupStatus.ACTIVE);

        assertTrue(reconciler.pass(ACCOUNT, CLUSTER));
        assertTrue(group.getNodes().isEmpty());
    }

    private void startVersionUpdate(Nodegroup group, String version, boolean force) {
        clock.advanceSeconds(1);
        group.setStatus(NodegroupStatus.UPDATING);
        group.setPendingUpdateId("upd-version");
        group.setPendingVersion(version);
        group.setPendingForce(force);
        group.setPendingSince(clock.instant());
        clock.advanceSeconds(1);
    }

    private static String issueCode(Nodegroup group) {
        Map<?, ?> health = (Map<?, ?>) group.getHealth();
        List<?> issues = (List<?>) health.get("issues");
        return issues.isEmpty() ? null : String.valueOf(((Map<?, ?>) issues.getFirst()).get("code"));
    }

    private static List<String> ids(Nodegroup group) {
        return group.getNodes().stream().map(EksNode::getInstanceId).toList();
    }

    private EksNode launched(String id, int index) {
        EksNode node = new EksNode();
        node.setInstanceId(id);
        node.setLaunchIndex(index);
        node.setVersion("1.33");
        node.setPrivateIp("10.0.9." + index);
        node.setNodeName("ip-10-0-9-" + index + ".ec2.internal");
        node.setLaunchedAt(clock.instant());
        return node;
    }

    private final class FakeStore implements EksNodeReconciler.Store {
        private final Object lock = new Object();
        private final List<Nodegroup> groups = new ArrayList<>();
        private final List<String> finished = new ArrayList<>();
        private Cluster cluster;

        FakeStore() {
            cluster = new Cluster();
            cluster.setName(CLUSTER);
            cluster.setAccountId(ACCOUNT);
            cluster.setTopology(Cluster.AGENTLESS_TOPOLOGY);
            cluster.setStatus(ClusterStatus.ACTIVE);
            cluster.setContainerId("server");
            cluster.setJoinToken("K10token::server:join");
            cluster.setVersion("1.33");
        }

        Nodegroup add(String name, int desired, NodegroupStatus status) {
            Nodegroup group = new Nodegroup();
            group.setNodegroupName(name);
            group.setNodegroupArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":nodegroup/" + CLUSTER + "/" + name + "/uuid-" + name);
            group.setVersion("1.33");
            group.setStatus(status);
            group.setCreatedAt(clock.instant());
            group.setSubnets(List.of("subnet-a", "subnet-b"));
            group.setInstanceTypes(List.of("t3.medium"));
            NodegroupScalingConfig scaling = new NodegroupScalingConfig();
            scaling.setMinSize(0);
            scaling.setMaxSize(10);
            scaling.setDesiredSize(desired);
            group.setScalingConfig(scaling);
            group.setHealth(new LinkedHashMap<>(Map.of("issues", new ArrayList<>())));
            groups.add(group);
            return group;
        }

        @Override
        public Object lock(String accountId, String clusterName) {
            return lock;
        }

        @Override
        public Optional<Cluster> cluster(String accountId, String clusterName) {
            return Optional.ofNullable(cluster);
        }

        @Override
        public List<Nodegroup> nodegroups(String accountId, String clusterName) {
            return cluster == null ? List.of() : List.copyOf(groups);
        }

        @Override
        public void save(String accountId, Nodegroup group) {
        }

        @Override
        public void delete(String accountId, Nodegroup group) {
            groups.remove(group);
        }

        @Override
        public void saveCluster(String accountId, Cluster saved) {
        }

        @Override
        public void finishUpdate(Cluster updated, String updateId, String errorCode, String failure) {
            finished.add(updateId + ":" + (failure == null ? "ok" : errorCode));
        }
    }

    private final class FakeRuntime implements EksNodeReconciler.Runtime {
        private final List<String> started = new ArrayList<>();
        private final List<String> terminated = new ArrayList<>();
        private final List<String> drained = new ArrayList<>();
        private final List<String> uncordoned = new ArrayList<>();
        private final Set<String> agents = new HashSet<>();
        private final Map<String, String> versions = new HashMap<>();
        private int nextHost = 20;
        private boolean readyAfterStart = true;
        private boolean drainSucceeds = true;
        private RuntimeException failure;
        private Consumer<EksNode> onEnsure;
        private Function<EksNode, NodeState> stateOverride;
        private Set<String> lastExpectedAgents;
        private Set<String> lastExpectedNodeNames;
        private final Map<String, EksNode> byName = new HashMap<>();

        @Override
        public void placeNode(Cluster cluster, Nodegroup group, EksNode node, Set<String> clusterAddressesInUse) {
            String address = "10.0.1." + nextHost++;
            node.setPrivateIp(address);
            node.setClusterIp(address);
            node.setAvailabilityZone("subnet-a".equals(node.getSubnetId()) ? "us-east-1a" : "us-east-1b");
            node.setNodeName(EksNodeLayout.ipNodeName(address, "us-east-1"));
            node.setImage("rancher/k3s:v" + node.getVersion() + ".1-k3s1");
        }

        @Override
        public Optional<String> readJoinToken(Cluster cluster) {
            return Optional.of("K10token::server:join");
        }

        @Override
        public boolean ensureAgent(Cluster cluster, Nodegroup group, EksNode node) {
            if (onEnsure != null) {
                onEnsure.accept(node);
            }
            if (failure != null) {
                throw failure;
            }
            byName.put(node.getNodeName(), node);
            versions.put(node.getNodeName(), node.getVersion());
            if (agents.add(node.getInstanceId())) {
                started.add(node.getInstanceId());
                return true;
            }
            return false;
        }

        @Override
        public void removeAgent(Cluster cluster, EksNode node, boolean terminate) {
            agents.remove(node.getInstanceId());
            if (terminate) {
                terminated.add(node.getInstanceId());
            }
        }

        @Override
        public Optional<Map<String, NodeState>> nodeStates(Cluster cluster) {
            Map<String, NodeState> states = new HashMap<>();
            for (Map.Entry<String, EksNode> entry : byName.entrySet()) {
                if (!agents.contains(entry.getValue().getInstanceId())) {
                    continue;
                }
                NodeState state = stateOverride != null ? stateOverride.apply(entry.getValue())
                        : new NodeState(readyAfterStart, false, "v" + versions.get(entry.getKey()) + ".1+k3s1");
                states.put(entry.getKey(), state);
            }
            return Optional.of(states);
        }

        @Override
        public boolean finishNode(Cluster cluster, Nodegroup group, EksNode node) {
            return true;
        }

        @Override
        public boolean drainNode(Cluster cluster, EksNode node, int timeoutSeconds) {
            drained.add(node.getInstanceId());
            return drainSucceeds;
        }

        @Override
        public void uncordonNode(Cluster cluster, EksNode node) {
            uncordoned.add(node.getInstanceId());
        }

        @Override
        public void removeOrphanAgents(Cluster cluster, Set<String> instanceIds) {
            lastExpectedAgents = Set.copyOf(instanceIds);
        }

        @Override
        public void deleteStaleNodes(Cluster cluster, Set<String> nodeNames, Map<String, NodeState> present) {
            lastExpectedNodeNames = Set.copyOf(nodeNames);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
