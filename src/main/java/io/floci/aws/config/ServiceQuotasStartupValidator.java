package io.floci.aws.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ServiceQuotasStartupValidator {

    private final EmulatorConfig config;

    @Inject
    public ServiceQuotasStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        validateStandardOnDemandVcpus(config.services().servicequotas().standardOnDemandVcpus());
        parseAppliedValues(config.services().servicequotas().appliedValues());
    }

    public static void validateStandardOnDemandVcpus(int value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "floci.services.servicequotas.standard-on-demand-vcpus must be nonnegative");
        }
    }

    /** {@code <serviceCode>/<quotaCode>=<value>} entries; a malformed one stops the service, as a bad setting should. */
    public static Map<String, Double> parseAppliedValues(Optional<List<String>> entries) {
        Map<String, Double> values = new HashMap<>();
        for (String entry : entries.orElse(List.of())) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int equals = entry.indexOf('=');
            String key = equals < 0 ? "" : entry.substring(0, equals).trim();
            int slash = key.indexOf('/');
            double value;
            try {
                value = equals < 0 ? -1 : Double.parseDouble(entry.substring(equals + 1).trim());
            } catch (NumberFormatException e) {
                value = -1;
            }
            if (slash <= 0 || slash == key.length() - 1 || value < 0) {
                throw new IllegalArgumentException("floci.services.servicequotas.applied-values entries must read"
                        + " <serviceCode>/<quotaCode>=<nonnegative value>, got: " + entry);
            }
            values.put(key, value);
        }
        return Map.copyOf(values);
    }
}
