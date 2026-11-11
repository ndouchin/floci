package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * {@code UpdateNodegroupConfig} request body. {@code updateConfig}, {@code nodeRepairConfig} and
 * {@code warmPoolConfig} stay generic JSON, as {@link Nodegroup} records them.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateNodegroupConfigRequest(Labels labels, Taints taints, NodegroupScalingConfig scalingConfig,
                                           Map<String, Object> updateConfig, Map<String, Object> nodeRepairConfig,
                                           Map<String, Object> warmPoolConfig, String clientRequestToken) {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Labels(Map<String, String> addOrUpdateLabels, List<String> removeLabels) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Taints(List<Map<String, Object>> addOrUpdateTaints, List<Map<String, Object>> removeTaints) {}
}
