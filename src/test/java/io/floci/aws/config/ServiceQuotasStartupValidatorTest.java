package io.floci.aws.config;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceQuotasStartupValidatorTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void negativeQuotaIsRejectedByStartupObserver(int value) {
        ServiceQuotasStartupValidator validator = validator(value);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.onStart(new StartupEvent()));
        assertEquals("floci.services.servicequotas.standard-on-demand-vcpus must be nonnegative",
                error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 7, Integer.MAX_VALUE})
    void nonnegativeQuotaIsAcceptedByStartupObserver(int value) {
        ServiceQuotasStartupValidator validator = validator(value);
        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ec2/L-1216C47A", "L-1216C47A=100", "ec2/=100", "ec2/L-1216C47A=-1", "ec2/L-1216C47A=many"})
    void aMalformedAppliedValueIsRejectedByStartupObserver(String entry) {
        EmulatorConfig config = config(5);
        when(config.services().servicequotas().appliedValues()).thenReturn(Optional.of(List.of(entry)));
        ServiceQuotasStartupValidator validator = new ServiceQuotasStartupValidator(config);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.onStart(new StartupEvent()));
        assertTrue(error.getMessage().startsWith("floci.services.servicequotas.applied-values"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ec2/L-1216C47A=100", "vpc/L-F678F1CE=10.5", "ebs/L-309BACF6=0"})
    void aWellFormedAppliedValueIsAcceptedByStartupObserver(String entry) {
        EmulatorConfig config = config(5);
        when(config.services().servicequotas().appliedValues()).thenReturn(Optional.of(List.of(entry)));
        assertDoesNotThrow(() -> new ServiceQuotasStartupValidator(config).onStart(new StartupEvent()));
        assertEquals(1, ServiceQuotasStartupValidator.parseAppliedValues(Optional.of(List.of(entry))).size());
    }

    private static ServiceQuotasStartupValidator validator(int value) {
        return new ServiceQuotasStartupValidator(config(value));
    }

    private static EmulatorConfig config(int value) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().servicequotas().standardOnDemandVcpus()).thenReturn(value);
        return config;
    }
}
