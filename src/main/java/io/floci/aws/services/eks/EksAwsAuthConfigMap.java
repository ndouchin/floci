package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the {@code kube-system/aws-auth} ConfigMap the way aws-iam-authenticator's ConfigMap mapper
 * does: {@code mapRoles} entries match a role's canonical ARN (the role without its path) and
 * {@code mapUsers} entries an IAM user's ARN, both case-insensitively.
 */
final class EksAwsAuthConfigMap {

    private static final Logger LOG = Logger.getLogger(EksAwsAuthConfigMap.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper NODE_YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    record Mapping(String username, List<String> groups) {}

    private EksAwsAuthConfigMap() {}

    /** The mapping for {@code canonicalArn} in a ConfigMap read as JSON, or empty when it has none. */
    static Optional<Mapping> find(String configMapJson, String canonicalArn) {
        if (configMapJson == null || configMapJson.isBlank() || canonicalArn == null) {
            return Optional.empty();
        }
        try {
            JsonNode data = JSON.readTree(configMapJson).path("data");
            Optional<Mapping> role = match(data.path("mapRoles").asText(""), "rolearn", canonicalArn);
            return role.isPresent() ? role : match(data.path("mapUsers").asText(""), "userarn", canonicalArn);
        } catch (Exception e) {
            LOG.warnv("Ignoring an aws-auth ConfigMap that does not parse: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@code mapRoles} with the node mapping EKS writes for a managed node group's role, or null when the role's
     * canonical ARN is mapped already: the role, {@code system:node:{{EC2PrivateDNSName}}}, and the groups
     * {@code system:bootstrappers} and {@code system:nodes}.
     */
    static String withNodeRole(String mapRolesYaml, String roleArn) {
        String canonical = EksWorkerAuthentication.canonicalArn(roleArn);
        try {
            if (match(mapRolesYaml == null ? "" : mapRolesYaml, "rolearn", canonical).isPresent()) {
                return null;
            }
            List<Map<String, Object>> entries = mapRolesYaml == null || mapRolesYaml.isBlank() ? new ArrayList<>()
                    : YAML.readValue(mapRolesYaml, new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> merged = new ArrayList<>(entries == null ? List.of() : entries);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("groups", List.of("system:bootstrappers", "system:nodes"));
            node.put("rolearn", canonical);
            node.put("username", "system:node:{{EC2PrivateDNSName}}");
            merged.add(node);
            return NODE_YAML.writeValueAsString(merged);
        } catch (Exception e) {
            LOG.warnv("Not adding node role {0} to an aws-auth ConfigMap that does not parse: {1}", roleArn,
                    e.getMessage());
            return null;
        }
    }

    private static Optional<Mapping> match(String yaml, String arnField, String canonicalArn) throws Exception {
        if (yaml.isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> entries = YAML.readValue(yaml, new TypeReference<>() {});
        if (entries == null) {
            return Optional.empty();
        }
        for (Map<String, Object> entry : entries) {
            Object arn = entry == null ? null : entry.get(arnField);
            if (arn != null && canonicalArn.equalsIgnoreCase(arn.toString().trim())) {
                List<String> groups = new ArrayList<>();
                if (entry.get("groups") instanceof List<?> list) {
                    list.forEach(group -> groups.add(String.valueOf(group)));
                }
                Object username = entry.get("username");
                return Optional.of(new Mapping(username == null ? null : username.toString(), List.copyOf(groups)));
            }
        }
        return Optional.empty();
    }
}
