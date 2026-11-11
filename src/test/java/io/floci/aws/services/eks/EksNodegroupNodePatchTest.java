package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.eks.model.Nodegroup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksNodegroupNodePatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void addsLabelsAndTaintsAndRecordsThemAsManaged() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"metadata":{"resourceVersion":"7","labels":{"kubernetes.io/hostname":"n"}},"spec":{}}
                """);

        JsonNode patch = MAPPER.readTree(EksClusterManager.buildNodePatch(node,
                labels("team", "payments"), List.of(taint("dedicated", "batch", "NoSchedule"))));

        assertEquals("7", patch.path("metadata").path("resourceVersion").asText());
        assertEquals("payments", patch.path("metadata").path("labels").path("team").asText());
        assertEquals("team", patch.path("metadata").path("annotations")
                .path(EksClusterManager.MANAGED_LABELS_ANNOTATION).asText());
        assertEquals("dedicated:NoSchedule", patch.path("metadata").path("annotations")
                .path(EksClusterManager.MANAGED_TAINTS_ANNOTATION).asText());
        assertEquals(1, patch.path("spec").path("taints").size());
        assertEquals("batch", patch.path("spec").path("taints").get(0).path("value").asText());
    }

    @Test
    void removesOnlyWhatAnEarlierSyncPutThere() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"metadata":{"labels":{"team":"payments","owner":"ops","topology.kubernetes.io/zone":"us-east-1a"},
                  "annotations":{"floci.io/eks-nodegroup-labels":"team","floci.io/eks-nodegroup-taints":"dedicated:NoSchedule"}},
                 "spec":{"taints":[{"key":"dedicated","value":"batch","effect":"NoSchedule"},
                                   {"key":"operator","effect":"NoExecute"}]}}
                """);

        JsonNode patch = MAPPER.readTree(EksClusterManager.buildNodePatch(node,
                labels("topology.kubernetes.io/zone", "us-east-1a"), List.of()));

        assertTrue(patch.path("metadata").path("labels").path("team").isNull());
        assertFalse(patch.path("metadata").path("labels").has("owner"));
        assertEquals(1, patch.path("spec").path("taints").size());
        assertEquals("operator", patch.path("spec").path("taints").get(0).path("key").asText());
        assertEquals("", patch.path("metadata").path("annotations")
                .path(EksClusterManager.MANAGED_LABELS_ANNOTATION).asText());
    }

    @Test
    void identityLabelsAreOverwrittenButNeverRemoved() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"metadata":{"labels":{"node.kubernetes.io/instance-type":"k3s"},
                  "annotations":{"floci.io/eks-nodegroup-labels":"node.kubernetes.io/instance-type"}},"spec":{}}
                """);

        JsonNode removed = MAPPER.readTree(EksClusterManager.buildNodePatch(node, labels(), List.of()));
        JsonNode overwritten = MAPPER.readTree(EksClusterManager.buildNodePatch(node,
                labels("node.kubernetes.io/instance-type", "m5.large"), List.of()));

        assertFalse(removed.path("metadata").path("labels").has("node.kubernetes.io/instance-type"));
        assertEquals("m5.large", overwritten.path("metadata").path("labels")
                .path("node.kubernetes.io/instance-type").asText());
    }

    @Test
    void lastManagedTaintRemovedClearsTheTaintList() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"metadata":{"annotations":{"floci.io/eks-nodegroup-labels":"","floci.io/eks-nodegroup-taints":"gpu:NoSchedule"}},
                 "spec":{"taints":[{"key":"gpu","effect":"NoSchedule"}]}}
                """);

        JsonNode patch = MAPPER.readTree(EksClusterManager.buildNodePatch(node, labels(), List.of()));

        assertTrue(patch.path("spec").has("taints"));
        assertTrue(patch.path("spec").path("taints").isNull());
    }

    @Test
    void nodeAlreadyInLineNeedsNoPatch() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"metadata":{"labels":{"team":"payments"},
                  "annotations":{"floci.io/eks-nodegroup-labels":"team","floci.io/eks-nodegroup-taints":"gpu:NoSchedule"}},
                 "spec":{"taints":[{"key":"gpu","value":"true","effect":"NoSchedule","timeAdded":"2026-11-10T00:00:00Z"}]}}
                """);

        assertNull(EksClusterManager.buildNodePatch(node, labels("team", "payments"),
                List.of(taint("gpu", "true", "NoSchedule"))));
    }

    @Test
    void nodegroupTaintsMapEksEffectsAndKeepTheLastOfAKeyAndEffect() {
        Nodegroup nodegroup = new Nodegroup();
        nodegroup.setTaints(List.of(
                Map.of("key", "gpu", "value", "a", "effect", "NO_SCHEDULE"),
                Map.of("key", "gpu", "value", "b", "effect", "NO_SCHEDULE"),
                Map.of("key", "spot", "effect", "PREFER_NO_SCHEDULE")));

        List<Map<String, String>> taints = EksClusterManager.nodegroupTaints(nodegroup);

        assertEquals(List.of(taint("gpu", "b", "NoSchedule"), taint("spot", null, "PreferNoSchedule")), taints);
    }

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            labels.put(pairs[i], pairs[i + 1]);
        }
        return labels;
    }

    private static Map<String, String> taint(String key, String value, String effect) {
        Map<String, String> taint = new LinkedHashMap<>();
        taint.put("key", key);
        if (value != null) {
            taint.put("value", value);
        }
        taint.put("effect", effect);
        return taint;
    }
}
