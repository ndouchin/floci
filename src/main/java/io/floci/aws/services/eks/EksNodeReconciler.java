package io.floci.aws.services.eks;

import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.eks.model.EksNode;
import io.floci.aws.services.eks.model.Nodegroup;
import io.floci.aws.services.eks.model.NodegroupScalingConfig;
import io.floci.aws.services.eks.model.NodegroupStatus;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Brings the k3s agents of a multi-node cluster in line with its managed node groups: one agent per node under
 * the node cap, launched, adopted, replaced, drained and removed as EKS's Auto Scaling groups and managed node
 * updates do, and the node groups' statuses and updates finished once their nodes are Ready. One pass runs per
 * cluster at a time; a pass reads and writes records under the cluster's lock and runs Docker and kubectl outside
 * it, so the API keeps answering while agents start.
 */
final class EksNodeReconciler {

    private static final Logger LOG = Logger.getLogger(EksNodeReconciler.class);

    /** What runs the agents: Docker and kubectl in the real service, a fake in unit tests. */
    interface Runtime {
        /** Gives a new node its subnet's zone, its address (VPC lease or cluster network) and its name. */
        void placeNode(Cluster cluster, Nodegroup group, EksNode node, Set<String> clusterAddressesInUse);

        Optional<String> readJoinToken(Cluster cluster);

        /**
         * Starts the node's agent, adopts the one running for it, or recreates it on its volume.
         *
         * @return whether a new container was created
         * @throws NodeLaunchException when the node could not be launched
         */
        boolean ensureAgent(Cluster cluster, Nodegroup group, EksNode node);

        /**
         * Stops the node's agent and deletes its Node object. With {@code terminate}, the node is gone for good:
         * its volume goes too and its address is returned; without, the node only loses its agent to the node cap.
         */
        void removeAgent(Cluster cluster, EksNode node, boolean terminate);

        /** The cluster's Node objects by name; empty when the API server cannot be read. */
        Optional<Map<String, NodeState>> nodeStates(Cluster cluster);

        /** What EKS adds once a node has joined: labels and taints changed since, and GPU capacity. */
        boolean finishNode(Cluster cluster, Nodegroup group, EksNode node);

        /** Labels the node out of load balancers, cordons and drains it; false when pods could not be evicted. */
        boolean drainNode(Cluster cluster, EksNode node, int timeoutSeconds);

        void uncordonNode(Cluster cluster, EksNode node);

        /** Removes the cluster's agents, found by label, whose instance ID is not in {@code instanceIds}. */
        void removeOrphanAgents(Cluster cluster, Set<String> instanceIds);

        /**
         * Maps the node group's role in {@code aws-auth} when the cluster authenticates through it alone, as EKS does
         * when it creates a managed node group; a cluster with access entries gets the role's access entry instead.
         */
        default void authorizeNodeRole(Cluster cluster, Nodegroup group) {
        }

        /** Deletes the Node objects no node, and no joined EC2 instance, of the cluster has. */
        void deleteStaleNodes(Cluster cluster, Set<String> nodeNames, Map<String, NodeState> present);
    }

    /** Where node groups and clusters are stored: EksService's storage, a map in unit tests. */
    interface Store {
        Object lock(String accountId, String clusterName);

        Optional<Cluster> cluster(String accountId, String clusterName);

        /** The cluster's node groups in creation order. */
        List<Nodegroup> nodegroups(String accountId, String clusterName);

        void save(String accountId, Nodegroup group);

        void delete(String accountId, Nodegroup group);

        void saveCluster(String accountId, Cluster cluster);

        void finishUpdate(Cluster cluster, String updateId, String errorCode, String failure);

        /** Runs a pass in the cluster's account and region, as a request for it would. */
        default boolean scoped(String accountId, String clusterName, Supplier<Boolean> pass) {
            return pass.get();
        }
    }

    record NodeState(boolean ready, boolean uninitialized, String kubeletVersion) {}

    /** A node that could not be launched, with the health issue code EKS would report for it. */
    static final class NodeLaunchException extends RuntimeException {
        private final String issueCode;
        private final boolean fatal;

        NodeLaunchException(String issueCode, String message, boolean fatal) {
            super(message);
            this.issueCode = issueCode;
            this.fatal = fatal;
        }

        String issueCode() {
            return issueCode;
        }

        boolean fatal() {
            return fatal;
        }
    }

    record Settings(int maxNodesPerCluster, int nodeReadyTimeoutSeconds, int nodeDrainTimeoutSeconds) {}

    private record ClusterRef(String accountId, String clusterName) {}

    /** A node to run, as a pass read it under the lock, with the version it was asked for. */
    private record NodeWork(Nodegroup group, EksNode node, String version) {}

    /** A node a rolling update drains before it removes it. */
    private record Drain(String groupArn, EksNode node, boolean force) {}

    /** A node a pass stops, for good or only past the node cap. */
    private record Removal(EksNode node, boolean terminate) {}

    private final Runtime runtime;
    private final Store store;
    private final Settings settings;
    private final Clock clock;
    private final ExecutorService executor;
    private final Map<String, AtomicLong> changes = new ConcurrentHashMap<>();
    private final Map<String, ClusterRef> clusters = new ConcurrentHashMap<>();
    private final Set<String> requested = ConcurrentHashMap.newKeySet();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Set<String> unsettled = ConcurrentHashMap.newKeySet();
    private final Set<String> replacingControlPlanes = ConcurrentHashMap.newKeySet();
    private final Map<String, String> capWarnings = new ConcurrentHashMap<>();
    private final Set<String> minimalStrategyLogged = ConcurrentHashMap.newKeySet();

    EksNodeReconciler(Runtime runtime, Store store, Settings settings, Clock clock) {
        this(runtime, store, settings, clock, Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "floci-eks-nodes");
            thread.setDaemon(true);
            return thread;
        }));
    }

    EksNodeReconciler(Runtime runtime, Store store, Settings settings, Clock clock, ExecutorService executor) {
        this.runtime = runtime;
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        this.executor = executor;
    }

    void shutdown() {
        executor.shutdownNow();
    }

    /** Asks for a pass over the cluster, after the one running if any. */
    void request(String accountId, String clusterName) {
        String key = key(accountId, clusterName);
        clusters.put(key, new ClusterRef(accountId, clusterName));
        changes.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        requested.add(key);
        schedule(key);
    }

    /** Asks again for the clusters whose last pass did not settle. */
    void retryUnsettled() {
        for (String key : List.copyOf(unsettled)) {
            requested.add(key);
            schedule(key);
        }
    }

    boolean isSettled(String accountId, String clusterName) {
        String key = key(accountId, clusterName);
        return !unsettled.contains(key) && !requested.contains(key) && !running.contains(key);
    }

    /** No node work runs while the cluster's server is being replaced; agents reconnect to the new one by themselves. */
    void replacingControlPlane(String accountId, String clusterName, boolean replacing) {
        String key = key(accountId, clusterName);
        if (replacing) {
            replacingControlPlanes.add(key);
        } else {
            replacingControlPlanes.remove(key);
            request(accountId, clusterName);
        }
    }

    void forget(String accountId, String clusterName) {
        String key = key(accountId, clusterName);
        unsettled.remove(key);
        capWarnings.remove(key);
    }

    private void schedule(String key) {
        if (running.add(key)) {
            try {
                executor.execute(() -> drain(key));
            } catch (RuntimeException e) {
                running.remove(key);
                LOG.debugv("EKS node reconcile for {0} not scheduled: {1}", key, e.getMessage());
            }
        }
    }

    private void drain(String key) {
        try {
            while (requested.remove(key)) {
                ClusterRef ref = clusters.get(key);
                if (ref == null) {
                    continue;
                }
                boolean settled;
                try {
                    settled = store.scoped(ref.accountId(), ref.clusterName(),
                            () -> pass(ref.accountId(), ref.clusterName()));
                } catch (RuntimeException e) {
                    LOG.warnv(e, "EKS node reconcile failed for cluster {0}", ref.clusterName());
                    settled = false;
                }
                if (settled) {
                    unsettled.remove(key);
                } else {
                    unsettled.add(key);
                }
            }
        } finally {
            running.remove(key);
            if (requested.contains(key)) {
                schedule(key);
            }
        }
    }

    /**
     * One pass over the cluster's nodes.
     *
     * @return whether the cluster is settled: no node group waits on its nodes, and nothing changed meanwhile
     */
    boolean pass(String accountId, String clusterName) {
        String key = key(accountId, clusterName);
        long generation = changes.computeIfAbsent(key, ignored -> new AtomicLong()).get();
        Cluster cluster;
        List<NodeWork> work = new ArrayList<>();
        List<Removal> removals = new ArrayList<>();
        List<Drain> drains = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Nodegroup> authorizing = new ArrayList<>();
        synchronized (store.lock(accountId, clusterName)) {
            Optional<Cluster> found = store.cluster(accountId, clusterName);
            if (found.isEmpty() || !found.get().isAgentless() || found.get().getStatus() == ClusterStatus.FAILED) {
                return true;
            }
            cluster = found.get();
            if (cluster.getContainerId() == null || replacingControlPlanes.contains(key)
                    || (cluster.getStatus() != ClusterStatus.ACTIVE && cluster.getStatus() != ClusterStatus.UPDATING)) {
                return cluster.getContainerId() == null && cluster.getStatus() == ClusterStatus.ACTIVE;
            }
            List<Nodegroup> groups = store.nodegroups(accountId, clusterName);
            Set<String> addresses = clusterAddresses(groups);
            int[] nodeCount = {groups.stream().mapToInt(group -> group.getNodes().size()).sum()};
            for (Nodegroup group : groups) {
                plan(cluster, group, addresses, nodeCount, removals, drains);
                if (group.getStatus() == NodegroupStatus.CREATING && group.getNodeRole() != null) {
                    authorizing.add(group);
                }
            }
            List<List<EksNode>> perGroup = new ArrayList<>();
            for (Nodegroup group : groups) {
                perGroup.add(group.getNodes());
            }
            Set<EksNode> underCap = new HashSet<>(EksNodeLayout.underCap(perGroup, settings.maxNodesPerCluster()));
            List<String> shortGroups = new ArrayList<>();
            for (Nodegroup group : groups) {
                int skipped = 0;
                for (EksNode node : group.getNodes()) {
                    seen.add(node.getInstanceId());
                    boolean runs = underCap.contains(node);
                    if (!runs && node.isRunning()) {
                        removals.add(new Removal(node.copy(), false));
                    }
                    node.setRunning(runs);
                    if (runs) {
                        work.add(new NodeWork(group, node.copy(), node.getVersion()));
                    } else {
                        node.setReady(false);
                        skipped++;
                    }
                }
                if (skipped > 0) {
                    shortGroups.add(group.getNodegroupName() + " (" + skipped + " of " + group.getNodes().size()
                            + " nodes)");
                }
                store.save(accountId, group);
            }
            warnAboutCap(key, clusterName, shortGroups);
        }

        if (cluster.getJoinToken() == null) {
            Optional<String> token = runtime.readJoinToken(cluster);
            if (token.isEmpty()) {
                return false;
            }
            synchronized (store.lock(accountId, clusterName)) {
                Optional<Cluster> current = store.cluster(accountId, clusterName);
                if (current.isEmpty()) {
                    return true;
                }
                current.get().setJoinToken(token.get());
                store.saveCluster(accountId, current.get());
                cluster = current.get();
            }
        }

        for (Nodegroup group : authorizing) {
            runtime.authorizeNodeRole(cluster, group);
        }
        Map<String, String> startedVersions = new HashMap<>();
        Set<String> created = new HashSet<>();
        Map<String, NodeLaunchException> failures = new HashMap<>();
        for (NodeWork item : work) {
            startedVersions.put(item.node().getInstanceId(), item.version());
            try {
                if (runtime.ensureAgent(cluster, item.group(), item.node())) {
                    created.add(item.node().getInstanceId());
                }
            } catch (NodeLaunchException e) {
                LOG.warnv("Could not launch node {0} of EKS node group {1}: {2}", item.node().getInstanceId(),
                        item.group().getNodegroupName(), e.getMessage());
                failures.put(item.node().getInstanceId(), e);
            } catch (RuntimeException e) {
                LOG.warnv("Could not launch node {0} of EKS node group {1}: {2}", item.node().getInstanceId(),
                        item.group().getNodegroupName(), e.getMessage());
                failures.put(item.node().getInstanceId(),
                        new NodeLaunchException("AsgInstanceLaunchFailures", e.getMessage(), false));
            }
        }
        for (Removal removal : removals) {
            removeQuietly(cluster, removal.node(), removal.terminate());
        }

        Set<String> readyNow = new HashSet<>();
        Map<String, NodeState> states = runtime.nodeStates(cluster).orElse(null);
        if (states != null) {
            for (NodeWork item : work) {
                EksNode node = item.node();
                if (failures.containsKey(node.getInstanceId()) || !runsItsVersion(states.get(node.getNodeName()), node)) {
                    continue;
                }
                if ((node.isReady() && !created.contains(node.getInstanceId()))
                        || runtime.finishNode(cluster, item.group(), node)) {
                    readyNow.add(node.getInstanceId());
                }
            }
        }

        Set<String> drained = new HashSet<>();
        Map<String, String> drainFailures = new HashMap<>();
        for (Drain drain : drains) {
            if (runtime.drainNode(cluster, drain.node(), settings.nodeDrainTimeoutSeconds()) || drain.force()) {
                drained.add(drain.node().getInstanceId());
                removeQuietly(cluster, drain.node(), true);
            } else {
                drainFailures.put(drain.groupArn(), drain.node().getInstanceId());
                runtime.uncordonNode(cluster, drain.node());
            }
        }

        boolean settled = true;
        List<EksNode> abandoned = new ArrayList<>();
        Set<String> expected = new HashSet<>();
        Set<String> expectedNames = new HashSet<>();
        synchronized (store.lock(accountId, clusterName)) {
            Optional<Cluster> current = store.cluster(accountId, clusterName);
            if (current.isEmpty() || !current.get().isAgentless()) {
                for (NodeWork item : work) {
                    if (created.contains(item.node().getInstanceId())) {
                        abandoned.add(item.node());
                    }
                }
                settled = true;
            } else {
                List<Nodegroup> groups = store.nodegroups(accountId, clusterName);
                Set<String> present = new HashSet<>();
                for (Nodegroup group : groups) {
                    if (!settle(cluster, group, seen, startedVersions, readyNow, created, failures, drained,
                            drainFailures, present, expected, expectedNames)) {
                        settled = false;
                    }
                    if (group.getStatus() == NodegroupStatus.DELETING && group.getNodes().isEmpty()) {
                        store.delete(accountId, group);
                        LOG.infov("EKS node group {0} of cluster {1} is deleted", group.getNodegroupName(), clusterName);
                    } else {
                        store.save(accountId, group);
                    }
                }
                for (NodeWork item : work) {
                    if (!present.contains(item.node().getInstanceId()) && created.contains(item.node().getInstanceId())) {
                        abandoned.add(item.node());
                    }
                }
            }
        }
        for (EksNode node : abandoned) {
            removeQuietly(cluster, node, true);
        }
        if (store.cluster(accountId, clusterName).isPresent()) {
            runtime.removeOrphanAgents(cluster, expected);
            if (states != null) {
                runtime.deleteStaleNodes(cluster, expectedNames, states);
            }
        }
        if (changes.get(key).get() != generation) {
            settled = false;
        }
        return settled;
    }

    /** Under the lock: launches and terminates node records so the node group matches its size and update. */
    private void plan(Cluster cluster, Nodegroup group, Set<String> addresses, int[] nodeCount,
                      List<Removal> removals, List<Drain> drains) {
        List<EksNode> nodes = group.getNodes();
        if (group.getStatus() == NodegroupStatus.DELETING) {
            for (EksNode node : nodes) {
                removals.add(new Removal(node.copy(), true));
            }
            nodeCount[0] -= nodes.size();
            nodes.clear();
            return;
        }
        if (group.getStatus() == NodegroupStatus.CREATE_FAILED || group.getStatus() == NodegroupStatus.DELETE_FAILED) {
            return;
        }
        String targetVersion = group.getPendingVersion() != null ? group.getPendingVersion() : group.getVersion();
        if (group.getPendingVersion() != null && group.getPendingSince() != null) {
            planRollingUpdate(cluster, group, addresses, nodeCount, drains, targetVersion);
        }
        int desired = desiredSize(group);
        List<EksNode> serving = nodes.stream().filter(node -> node.getReplaces() == null
                || nodes.stream().noneMatch(old -> old.getInstanceId().equals(node.getReplaces()))).toList();
        int launching = desired - serving.size();
        for (int i = 0; i < launching; i++) {
            nodes.add(launch(cluster, group, addresses, targetVersion, null));
            nodeCount[0]++;
        }
        if (launching < 0) {
            List<EksNode> rolling = drains.stream().map(Drain::node).toList();
            List<EksNode> candidates = serving.stream()
                    .filter(node -> rolling.stream().noneMatch(old -> old.getInstanceId().equals(node.getInstanceId())))
                    .toList();
            for (EksNode node : EksNodeLayout.nodesToRemove(candidates, -launching)) {
                nodes.removeIf(existing -> existing.getInstanceId().equals(node.getInstanceId()));
                removals.add(new Removal(node.copy(), true));
                nodeCount[0]--;
            }
        }
    }

    /**
     * EKS's default update strategy: per batch of {@code maxUnavailable}, launch a replacement on the new version,
     * wait for it to be Ready, then drain and terminate the node it replaces. Without a free slot under the node
     * cap there is no room to surge, so the old node is drained and terminated first, as EKS's minimal strategy
     * does, and its replacement launches after.
     */
    private void planRollingUpdate(Cluster cluster, Nodegroup group, Set<String> addresses, int[] nodeCount,
                                   List<Drain> drains, String targetVersion) {
        List<EksNode> nodes = group.getNodes();
        Instant since = group.getPendingSince();
        List<EksNode> old = nodes.stream().filter(node -> node.getReplaces() == null
                && (node.getLaunchedAt() == null || node.getLaunchedAt().isBefore(since)))
                .sorted(Comparator.comparingInt(EksNode::getLaunchIndex)).toList();
        Map<String, EksNode> replacementOf = new HashMap<>();
        for (EksNode node : nodes) {
            if (node.getReplaces() != null) {
                replacementOf.put(node.getReplaces(), node);
            }
        }
        List<EksNode> inFlight = old.stream().filter(node -> replacementOf.containsKey(node.getInstanceId())).toList();
        boolean force = group.isPendingForce();
        if (!inFlight.isEmpty()) {
            for (EksNode node : inFlight) {
                if (replacementOf.get(node.getInstanceId()).isReady()) {
                    drains.add(new Drain(group.getNodegroupArn(), node.copy(), force));
                }
            }
            return;
        }
        boolean launchedNotReady = nodes.stream().anyMatch(node -> node.getLaunchedAt() != null
                && !node.getLaunchedAt().isBefore(since) && !node.isReady());
        if (old.isEmpty() || launchedNotReady) {
            return;
        }
        int batch = Math.max(1, Math.min(maxUnavailable(group), old.size()));
        boolean surge = settings.maxNodesPerCluster() <= 0
                || nodeCount[0] + batch <= settings.maxNodesPerCluster();
        for (EksNode node : old.subList(0, batch)) {
            if (surge) {
                nodes.add(launch(cluster, group, addresses, targetVersion, node.getInstanceId()));
                nodeCount[0]++;
            } else {
                if (minimalStrategyLogged.add(group.getNodegroupArn() + "/" + group.getPendingUpdateId())) {
                    LOG.warnv("EKS node group {0} has no free node under max-nodes-per-cluster ({1}) to launch a"
                            + " replacement before draining; it terminates each node before launching its"
                            + " replacement, as EKS's minimal update strategy does", group.getNodegroupName(),
                            settings.maxNodesPerCluster());
                }
                drains.add(new Drain(group.getNodegroupArn(), node.copy(), force));
            }
        }
    }

    private EksNode launch(Cluster cluster, Nodegroup group, Set<String> addresses, String version, String replaces) {
        int index = group.getLaunchCounter();
        group.setLaunchCounter(index + 1);
        EksNode node = new EksNode();
        node.setInstanceId(EksNodeLayout.instanceId(group.getNodegroupArn(), index));
        node.setLaunchIndex(index);
        node.setVersion(version);
        node.setReplaces(replaces);
        node.setLaunchedAt(clock.instant());
        node.setInstanceType(group.getInstanceTypes() == null || group.getInstanceTypes().isEmpty()
                ? "t3.medium" : group.getInstanceTypes().getFirst());
        node.setSubnetId(EksNodeLayout.nextSubnet(group.getSubnets(), group.getNodes()));
        runtime.placeNode(cluster, group, node, addresses);
        if (node.getPrivateIp() != null) {
            addresses.add(node.getPrivateIp());
        }
        LOG.infov("EKS node group {0} launches node {1} ({2}, {3})", group.getNodegroupName(), node.getInstanceId(),
                node.getNodeName(), node.getAvailabilityZone());
        return node;
    }

    /** Under the lock, after the Docker work: records what the pass did and moves the node group's status on. */
    private boolean settle(Cluster cluster, Nodegroup group, Set<String> seen, Map<String, String> startedVersions,
                           Set<String> readyNow, Set<String> created, Map<String, NodeLaunchException> failures,
                           Set<String> drained, Map<String, String> drainFailures, Set<String> present,
                           Set<String> expected, Set<String> expectedNames) {
        boolean allReady = true;
        NodeLaunchException failure = null;
        boolean fatal = false;
        group.getNodes().removeIf(node -> drained.contains(node.getInstanceId()));
        Set<String> ids = new HashSet<>();
        group.getNodes().forEach(node -> ids.add(node.getInstanceId()));
        group.getNodes().stream().filter(node -> node.getReplaces() != null && !ids.contains(node.getReplaces()))
                .forEach(node -> node.setReplaces(null));
        for (EksNode node : group.getNodes()) {
            present.add(node.getInstanceId());
            if (!seen.contains(node.getInstanceId())) {
                allReady = false;
                continue;
            }
            if (!node.isRunning()) {
                continue;
            }
            expected.add(node.getInstanceId());
            expectedNames.add(node.getNodeName());
            String started = startedVersions.get(node.getInstanceId());
            if (started == null || !Objects.equals(started, node.getVersion())) {
                allReady = false;
                continue;
            }
            NodeLaunchException nodeFailure = failures.get(node.getInstanceId());
            if (nodeFailure != null) {
                failure = nodeFailure;
                fatal |= nodeFailure.fatal();
                node.setReady(false);
                allReady = false;
                continue;
            }
            if (created.contains(node.getInstanceId())) {
                node.setReady(false);
            }
            if (readyNow.contains(node.getInstanceId())) {
                node.setReady(true);
            } else {
                node.setReady(false);
                allReady = false;
            }
        }
        boolean sized = group.getStatus() == NodegroupStatus.DELETING
                || group.getNodes().stream().filter(node -> node.getReplaces() == null).count() >= desiredSize(group)
                || group.getStatus() == NodegroupStatus.CREATE_FAILED;
        String drainFailure = drainFailures.get(group.getNodegroupArn());
        return switch (group.getStatus()) {
            case CREATING -> settleCreating(group, allReady && sized, failure, fatal);
            case UPDATING -> settleUpdating(cluster, group, allReady && sized, drainFailure, failure);
            case ACTIVE, DEGRADED -> settleActive(group, failure, allReady && sized);
            case DELETING -> group.getNodes().isEmpty();
            default -> true;
        };
    }

    private boolean settleCreating(Nodegroup group, boolean ready, NodeLaunchException failure, boolean fatal) {
        if (ready && failure == null) {
            group.setStatus(NodegroupStatus.ACTIVE);
            group.setModifiedAt(clock.instant());
            LOG.infov("EKS node group {0} is ACTIVE", group.getNodegroupName());
            return true;
        }
        Instant since = group.getCreatedAt() != null ? group.getCreatedAt() : clock.instant();
        boolean timedOut = clock.instant().isAfter(since.plusSeconds(settings.nodeReadyTimeoutSeconds()));
        if (fatal || timedOut) {
            String code = failure != null ? failure.issueCode() : "NodeCreationFailure";
            String message = failure != null ? failure.getMessage()
                    : "Instances failed to join the kubernetes cluster within "
                    + settings.nodeReadyTimeoutSeconds() + " seconds";
            group.setStatus(NodegroupStatus.CREATE_FAILED);
            group.setModifiedAt(clock.instant());
            group.setHealth(health(code, message, group));
            LOG.warnv("EKS node group {0} is CREATE_FAILED ({1}): {2}", group.getNodegroupName(), code, message);
            return true;
        }
        return false;
    }

    private boolean settleUpdating(Cluster cluster, Nodegroup group, boolean ready, String drainFailure,
                                   NodeLaunchException failure) {
        if (drainFailure != null) {
            String message = "Reached max retries while trying to evict pods from node " + drainFailure
                    + " in node group " + group.getNodegroupName();
            finishGroupUpdate(cluster, group, "PodEvictionFailure", message);
            group.setHealth(health("PodEvictionFailure", message, group));
            group.getNodes().forEach(node -> node.setReplaces(null));
            return false;
        }
        boolean rolled = group.getPendingSince() == null || group.getNodes().stream()
                .noneMatch(node -> node.getReplaces() != null || node.getLaunchedAt() == null
                        || node.getLaunchedAt().isBefore(group.getPendingSince()));
        if (ready && rolled && failure == null) {
            if (group.getPendingVersion() != null) {
                group.setVersion(group.getPendingVersion());
                group.setReleaseVersion(group.getPendingReleaseVersion() != null ? group.getPendingReleaseVersion()
                        : EksNodeLayout.releaseVersion(group.getPendingVersion()));
            }
            finishGroupUpdate(cluster, group, null, null);
            return true;
        }
        Instant since = group.getPendingSince() != null ? group.getPendingSince()
                : group.getModifiedAt() != null ? group.getModifiedAt() : clock.instant();
        if (clock.instant().isAfter(since.plusSeconds(settings.nodeReadyTimeoutSeconds()
                + (long) settings.nodeDrainTimeoutSeconds() * Math.max(1, group.getNodes().size())))) {
            String code = failure != null ? failure.issueCode() : "NodeCreationFailure";
            finishGroupUpdate(cluster, group, code, failure != null ? failure.getMessage()
                    : "Nodes of node group " + group.getNodegroupName() + " were not Ready in time");
            return true;
        }
        return false;
    }

    private void finishGroupUpdate(Cluster cluster, Nodegroup group, String code, String failure) {
        String updateId = group.getPendingUpdateId();
        if (group.getPendingLaunchTemplate() != null && failure == null) {
            group.setLaunchTemplate(group.getPendingLaunchTemplate());
        }
        group.setPendingUpdateId(null);
        group.setPendingVersion(null);
        group.setPendingReleaseVersion(null);
        group.setPendingLaunchTemplate(null);
        group.setPendingForce(false);
        group.setPendingSince(null);
        group.setStatus(NodegroupStatus.ACTIVE);
        group.setModifiedAt(clock.instant());
        if (updateId != null) {
            store.finishUpdate(cluster, updateId, code, failure);
        }
        LOG.infov("EKS node group {0} update {1} {2}", group.getNodegroupName(), updateId,
                failure == null ? "is Successful" : "Failed: " + failure);
    }

    private boolean settleActive(Nodegroup group, NodeLaunchException failure, boolean ready) {
        if (failure != null) {
            if (group.getStatus() != NodegroupStatus.DEGRADED) {
                group.setStatus(NodegroupStatus.DEGRADED);
                group.setHealth(health(failure.issueCode(), failure.getMessage(), group));
                LOG.warnv("EKS node group {0} is DEGRADED: {1}", group.getNodegroupName(), failure.getMessage());
            }
            return false;
        }
        if (group.getStatus() == NodegroupStatus.DEGRADED && ready) {
            group.setStatus(NodegroupStatus.ACTIVE);
            Map<String, List<Object>> healthy = new LinkedHashMap<>();
            healthy.put("issues", new ArrayList<>());
            group.setHealth(healthy);
        }
        return ready;
    }

    private static Map<String, List<Object>> health(String code, String message, Nodegroup group) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("code", code);
        issue.put("message", message);
        List<String> resources = new ArrayList<>();
        if (group.getResources() instanceof Map<?, ?> resourceMap
                && resourceMap.get("autoScalingGroups") instanceof List<?> asgs) {
            for (Object asg : asgs) {
                if (asg instanceof Map<?, ?> asgMap && asgMap.get("name") != null) {
                    resources.add(asgMap.get("name").toString());
                }
            }
        }
        if (resources.isEmpty()) {
            resources.add(group.getNodegroupName());
        }
        issue.put("resourceIds", resources);
        Map<String, List<Object>> health = new LinkedHashMap<>();
        health.put("issues", new ArrayList<>(List.of(issue)));
        return health;
    }

    private boolean runsItsVersion(NodeState state, EksNode node) {
        if (state == null || !state.ready() || state.uninitialized()) {
            return false;
        }
        String minor = EksNodeLayout.imageMinor(node.getImage());
        return minor == null || state.kubeletVersion() == null || state.kubeletVersion().startsWith("v" + minor + ".");
    }

    private void removeQuietly(Cluster cluster, EksNode node, boolean terminate) {
        try {
            runtime.removeAgent(cluster, node, terminate);
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove node {0} of EKS cluster {1}: {2}; the next pass retries",
                    node.getInstanceId(), cluster.getName(), e.getMessage());
        }
    }

    private void warnAboutCap(String key, String clusterName, List<String> shortGroups) {
        String warning = shortGroups.isEmpty() ? null : String.join(", ", shortGroups);
        if (warning == null) {
            capWarnings.remove(key);
        } else if (!warning.equals(capWarnings.put(key, warning))) {
            LOG.warnv("EKS cluster {0} asks for more nodes than max-nodes-per-cluster ({1}) allows; these node"
                    + " groups run without a k3s agent for some nodes: {2}", clusterName,
                    settings.maxNodesPerCluster(), warning);
        }
    }

    private static Set<String> clusterAddresses(List<Nodegroup> groups) {
        Set<String> addresses = new LinkedHashSet<>();
        for (Nodegroup group : groups) {
            for (EksNode node : group.getNodes()) {
                if (node.getPrivateIp() != null) {
                    addresses.add(node.getPrivateIp());
                }
                if (node.getClusterIp() != null) {
                    addresses.add(node.getClusterIp());
                }
            }
        }
        return addresses;
    }

    static int desiredSize(Nodegroup group) {
        NodegroupScalingConfig scaling = group.getScalingConfig();
        if (scaling == null) {
            return 1;
        }
        if (scaling.getDesiredSize() != null) {
            return Math.max(0, scaling.getDesiredSize());
        }
        return scaling.getMinSize() != null ? Math.max(0, scaling.getMinSize()) : 1;
    }

    static int maxUnavailable(Nodegroup group) {
        if (group.getUpdateConfig() instanceof Map<?, ?> config) {
            Object count = config.get("maxUnavailable");
            if (count instanceof Number number && number.intValue() > 0) {
                return number.intValue();
            }
            Object percentage = config.get("maxUnavailablePercentage");
            if (percentage instanceof Number number && number.intValue() > 0) {
                return Math.max(1, (int) Math.floor(group.getNodes().size() * number.intValue() / 100.0));
            }
        }
        return 1;
    }

    private static String key(String accountId, String clusterName) {
        return accountId + "/" + clusterName;
    }
}
