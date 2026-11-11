package io.floci.aws.services.eks;

import io.floci.aws.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.util.Set;

/**
 * Refuses to start Floci with an invalid {@code floci.services.eks.endpoint-host} or load balancer setting. Checked once here, before
 * persisted clusters are restored, so a typo stops the boot instead of failing every cluster on restore.
 * Metadata-only EKS (disabled or mock) never uses the setting, so it is not checked there.
 */
@ApplicationScoped
public class EksStartupValidator {

    private final EmulatorConfig config;

    @Inject
    public EksStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent ignored) {
        if (config.services().eks().enabled() && !config.services().eks().mock()) {
            EksClusterManager.endpointHostFrom(config.services().eks().endpointHost());
            String mode = config.services().eks().loadBalancer().mode();
            if (!Set.of(EksClusterManager.LOAD_BALANCER_EMULATED, EksClusterManager.LOAD_BALANCER_SERVICELB,
                    EksClusterManager.LOAD_BALANCER_OFF).contains(mode)) {
                throw new IllegalStateException("Invalid floci.services.eks.load-balancer.mode value '" + mode
                        + "': allowed values are emulated, servicelb and off");
            }
            String hostPorts = config.services().eks().loadBalancer().hostPorts();
            if (!Set.of("dynamic", "same-as-service", "none").contains(hostPorts)) {
                throw new IllegalStateException("Invalid floci.services.eks.load-balancer.host-ports value '"
                        + hostPorts + "': allowed values are dynamic, same-as-service and none");
            }
        }
    }
}
