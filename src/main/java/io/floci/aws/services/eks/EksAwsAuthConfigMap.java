package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jboss.logging.Logger;

import java.util.ArrayList;
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
