package io.floci.aws.services.servicequotas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Replays the Service Quotas calls {@code rosa create account-roles} and {@code rosa verify quota} make (rosa
 * v1.2.68, {@code pkg/aws/quota.go} and {@code pkg/policy/policy_service.go}): for each quota, ListServiceQuotas of its
 * service code, paginated, then the quota's value against rosa's DesiredValue; and GetServiceQuota of IAM's managed
 * policies per role. With AWS's default values the check fails where it fails on a new AWS account, on the On-Demand
 * Standard instances vCPU quota; raised to rosa's values, it passes.
 */
class ServiceQuotasRosaPreflightTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";

    /** rosa v1.2.68 {@code serviceQuotaServices}: service code, quota code, DesiredValue. */
    private static final List<String[]> ROSA_QUOTAS = List.of(
            new String[]{"ec2", "L-0263D0A3", "5"},
            new String[]{"ec2", "L-1216C47A", "100"},
            new String[]{"vpc", "L-F678F1CE", "5"},
            new String[]{"vpc", "L-A4707A72", "5"},
            new String[]{"vpc", "L-DF5E4CA3", "5000"},
            new String[]{"ebs", "L-D18FCD1D", "50"},
            new String[]{"ebs", "L-309BACF6", "300"},
            new String[]{"ebs", "L-B3A130E6", "300000"},
            new String[]{"ebs", "L-FD252861", "50"},
            new String[]{"elasticloadbalancing", "L-53DA6B97", "50"},
            new String[]{"elasticloadbalancing", "L-E9E9831D", "20"});

    @Test
    void withAwsDefaultsRosaFailsOnlyOnTheOnDemandStandardVcpuQuota() {
        ServiceQuotasService service = service(5, Optional.empty());

        assertEquals(List.of("ec2/L-1216C47A"), failingQuotas(service));
    }

    @Test
    void withTheValuesRaisedToRosasDesiredValuesRosaPasses() {
        List<String> raised = ROSA_QUOTAS.stream().map(quota -> quota[0] + "/" + quota[1] + "=" + quota[2]).toList();
        ServiceQuotasService service = service(5, Optional.of(raised));

        assertEquals(List.of(), failingQuotas(service));
    }

    @Test
    void raisingOnlyTheVcpuQuotaIsEnoughForRosa() {
        assertEquals(List.of(), failingQuotas(service(100, Optional.empty())));
    }

    // AWS defaults: VPC https://docs.aws.amazon.com/vpc/latest/userguide/amazon-vpc-limits.html,
    // EBS https://docs.aws.amazon.com/general/latest/gr/ebs-service.html,
    // ELB https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-limits.html and
    // https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-limits.html,
    // IAM https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_iam-quotas.html
    @ParameterizedTest
    @CsvSource({
            "ec2, L-0263D0A3, EC2-VPC Elastic IPs, 5",
            "vpc, L-F678F1CE, VPCs per Region, 5",
            "vpc, L-A4707A72, Internet gateways per Region, 5",
            "vpc, L-DF5E4CA3, Network interfaces per Region, 5000",
            "ebs, L-D18FCD1D, 'Storage for General Purpose SSD (gp2) volumes, in TiB', 50",
            "ebs, L-309BACF6, Snapshots per Region, 100000",
            "ebs, L-B3A130E6, IOPS for Provisioned IOPS SSD (io1) volumes, 300000",
            "ebs, L-FD252861, 'Storage for Provisioned IOPS SSD (io1) volumes, in TiB', 50",
            "elasticloadbalancing, L-53DA6B97, Application Load Balancers per Region, 50",
            "elasticloadbalancing, L-E9E9831D, Classic Load Balancers per Region, 20",
            "iam, L-0DA4ABF3, Managed policies per role, 10"
    })
    void eachQuotaRosaChecksHasItsAwsDefault(String serviceCode, String quotaCode, String name, double value) {
        ServiceQuotasService service = service(5, Optional.empty());

        JsonNode quota = service.getServiceQuota(serviceCode, quotaCode, REGION, ACCOUNT_ID).path("Quota");
        assertEquals(name, quota.path("QuotaName").asText());
        assertEquals(value, quota.path("Value").asDouble());
        assertEquals(value, service.getAwsDefaultServiceQuota(serviceCode, quotaCode, REGION, ACCOUNT_ID)
                .path("Quota").path("Value").asDouble());
    }

    @Test
    void ebsStorageDefaultsAreHigherOutsideTheOriginalRegions() {
        ServiceQuotasService service = service(5, Optional.empty());

        assertEquals(300.0, service.getServiceQuota("ebs", "L-D18FCD1D", "af-south-1", ACCOUNT_ID)
                .path("Quota").path("Value").asDouble());
        assertEquals(50.0, service.getServiceQuota("ebs", "L-FD252861", "eu-west-1", ACCOUNT_ID)
                .path("Quota").path("Value").asDouble());
    }

    @Test
    void anAppliedValueChangesTheAppliedQuotaButNotTheAwsDefault() {
        ServiceQuotasService service = service(5, Optional.of(List.of("vpc/L-F678F1CE=10")));

        assertEquals(10.0, service.getServiceQuota("vpc", "L-F678F1CE", REGION, ACCOUNT_ID)
                .path("Quota").path("Value").asDouble());
        assertEquals(5.0, service.getAwsDefaultServiceQuota("vpc", "L-F678F1CE", REGION, ACCOUNT_ID)
                .path("Quota").path("Value").asDouble());
    }

    @Test
    void anAppliedValueForTheVcpuQuotaWinsOverTheVcpuSetting() {
        ServiceQuotasService service = service(5, Optional.of(List.of("ec2/L-1216C47A=256")));

        assertEquals(256.0, service.getServiceQuota("ec2", "L-1216C47A", REGION, ACCOUNT_ID)
                .path("Quota").path("Value").asDouble());
    }

    @Test
    void aMalformedAppliedValueStopsTheService() {
        assertThrows(IllegalArgumentException.class, () -> service(5, Optional.of(List.of("vpc/L-F678F1CE"))));
        assertThrows(IllegalArgumentException.class, () -> service(5, Optional.of(List.of("vpc/L-F678F1CE=-1"))));
        assertThrows(IllegalArgumentException.class, () -> service(5, Optional.of(List.of("L-F678F1CE=10"))));
    }

    /** rosa's ValidateQuota: the quotas whose value is below rosa's DesiredValue, as service/code. */
    private static List<String> failingQuotas(ServiceQuotasService service) {
        List<String> failing = new ArrayList<>();
        for (String[] quota : ROSA_QUOTAS) {
            List<JsonNode> listed = new ArrayList<>();
            String token = null;
            do {
                JsonNode page = service.listServiceQuotas(quota[0], null, null, token, null, REGION, ACCOUNT_ID);
                page.path("Quotas").forEach(listed::add);
                token = page.hasNonNull("NextToken") ? page.path("NextToken").asText() : null;
            } while (token != null);
            JsonNode found = listed.stream().filter(entry -> quota[1].equals(entry.path("QuotaCode").asText()))
                    .findFirst().orElseThrow(() -> new AssertionError("rosa cannot find " + quota[1]));
            if (found.path("Value").asDouble() < Double.parseDouble(quota[2])) {
                failing.add(quota[0] + "/" + quota[1]);
            }
        }
        JsonNode policies = service.getServiceQuota("iam", "L-0DA4ABF3", REGION, ACCOUNT_ID).path("Quota");
        assertTrue(policies.path("Value").asDouble() >= 1, "rosa attaches one policy to each account role");
        return failing;
    }

    private static ServiceQuotasService service(int vcpus, Optional<List<String>> appliedValues) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().servicequotas().standardOnDemandVcpus()).thenReturn(vcpus);
        when(config.services().servicequotas().appliedValues()).thenReturn(appliedValues);
        return new ServiceQuotasService(new InMemoryStorageFactory(ACCOUNT_ID), new ObjectMapper(), config);
    }
}
