package io.floci.aws.services.eks;

import io.floci.aws.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class EksNodeConfigTest {

    @Inject
    EmulatorConfig config;

    @Test
    void nodeCapAndTimeoutsHaveDefaults() {
        assertEquals(5, config.services().eks().maxNodesPerCluster());
        assertEquals(900, config.services().eks().nodeReadyTimeoutSeconds());
        assertEquals(900, config.services().eks().nodeDrainTimeoutSeconds());
    }
}
