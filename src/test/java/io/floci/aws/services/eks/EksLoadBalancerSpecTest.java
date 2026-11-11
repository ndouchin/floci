package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One row per rule of the load balancer map's EKS part (b.1, b.3), with the source each comes from. */
class EksLoadBalancerSpecTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UID = "0f9e2c1a-7b3d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String CLUSTER = "demo";
    private static final String REGION = "us-west-2";

    // The AWS cloud provider makes a Classic Load Balancer named "a" + the Service UID without dashes, cut to 32
    // characters, and writes its DNS name as status.loadBalancer.ingress[].hostname.
    // https://github.com/kubernetes/cloud-provider/blob/3b8b147b3b97331a2c920ec39f188133f01dc319/cloud.go#L106-L115
    // https://github.com/kubernetes/cloud-provider-aws/blob/3774b6407a490818c9f5cb0ad4fef2ac562fa299/pkg/providers/v1/aws.go#L2896-L2906
    // Internet-facing CLB DNS names: <name>-<number>.<region>.elb.amazonaws.com
    // https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-internet-facing-load-balancers.html
    @Test
    void defaultServiceGetsAnInternetFacingClassicLoadBalancer() {
        EksLoadBalancerSpec spec = spec(service(Map.of(), null));

        assertTrue(spec.handled());
        assertEquals(EksLoadBalancerSpec.Kind.CLASSIC, spec.kind());
        assertFalse(spec.internal());
        assertEquals("a0f9e2c1a7b3d4e5f8a9b0c1d2e3f4a5", spec.loadBalancerName());
        assertTrue(spec.hostname().matches("a0f9e2c1a7b3d4e5f8a9b0c1d2e3f4a5-\\d{10}\\.us-west-2\\.elb\\.amazonaws\\.com"),
                spec.hostname());
        assertEquals(spec.hostname(), spec(service(Map.of(), null)).hostname(), "the same Service, the same name");
    }

    // The cloud provider makes the CLB internal when service.beta.kubernetes.io/aws-load-balancer-internal is set
    // and not "false"; internal CLB DNS names start with internal-.
    // https://github.com/kubernetes/cloud-provider-aws/blob/3774b6407a490818c9f5cb0ad4fef2ac562fa299/pkg/providers/v1/aws.go#L2473-L2480
    // https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-internal-load-balancers.html
    @ParameterizedTest
    @CsvSource({"true, true", "0.0.0.0/0, true", "false, false"})
    void internalAnnotationMakesAnInternalClassicLoadBalancer(String value, boolean internal) {
        EksLoadBalancerSpec spec = spec(service(Map.of("service.beta.kubernetes.io/aws-load-balancer-internal", value), null));

        assertEquals(internal, spec.internal());
        assertEquals(internal, spec.hostname().startsWith("internal-a0f9e2c1a7b3d4e5f8a9b0c1d2e3f4a5-"), spec.hostname());
    }

    // aws-load-balancer-type: nlb makes the cloud provider create an NLB named a<uid32>; NLB DNS names read
    // <name>-<id>.elb.<region>.amazonaws.com.
    // https://github.com/kubernetes/cloud-provider-aws/blob/3774b6407a490818c9f5cb0ad4fef2ac562fa299/pkg/providers/v1/aws_loadbalancer.go#L84-L96
    // https://docs.aws.amazon.com/elasticloadbalancing/latest/network/network-load-balancers.html
    @Test
    void nlbTypeAnnotationGetsACloudProviderNetworkLoadBalancer() {
        EksLoadBalancerSpec spec = spec(service(Map.of("service.beta.kubernetes.io/aws-load-balancer-type", "nlb"), null));

        assertEquals(EksLoadBalancerSpec.Kind.NETWORK, spec.kind());
        assertTrue(spec.hostname().matches("a0f9e2c1a7b3d4e5f8a9b0c1d2e3f4a5-[0-9a-f]{16}\\.elb\\.us-west-2\\.amazonaws\\.com"),
                spec.hostname());
    }

    // The AWS Load Balancer Controller handles class service.k8s.aws/nlb, and the types external and nlb-ip the cloud
    // provider leaves to it; it names the NLB k8s-<ns, 8>-<name, 8>-<sha256(cluster, uid, scheme), 10>, internal
    // unless aws-load-balancer-scheme says internet-facing, and writes one ingress entry with the ports.
    // https://github.com/kubernetes-sigs/aws-load-balancer-controller/blob/fdcdb1e5864e1603083f92ef642ee12b19568e99/pkg/service/model_build_load_balancer.go#L577-L595
    // https://github.com/kubernetes-sigs/aws-load-balancer-controller/blob/fdcdb1e5864e1603083f92ef642ee12b19568e99/controllers/service/service_controller.go#L252-L316
    // https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/service/nlb/
    @Test
    void loadBalancerControllerClassGetsAnInternalNlbNamedAsTheControllerNamesIt() throws Exception {
        EksLoadBalancerSpec spec = spec(service(Map.of(), "service.k8s.aws/nlb"));

        assertEquals(EksLoadBalancerSpec.Kind.CONTROLLER_NLB, spec.kind());
        assertTrue(spec.internal());
        assertEquals("k8s-teama-webfront-" + sha256(CLUSTER + UID + "internal").substring(0, 10), spec.loadBalancerName());
        assertTrue(spec.hostname().startsWith(spec.loadBalancerName() + "-"), spec.hostname());
        assertTrue(spec.hostname().endsWith(".elb.us-west-2.amazonaws.com"));
        JsonNode ingress = JSON.valueToTree(spec.ingress());
        assertEquals(1, ingress.size());
        assertEquals(spec.hostname(), ingress.get(0).path("hostname").asText());
        assertEquals(80, ingress.get(0).path("ports").get(0).path("port").asInt());
        assertEquals("TCP", ingress.get(0).path("ports").get(0).path("protocol").asText());
    }

    @ParameterizedTest
    @CsvSource({"external", "nlb-ip"})
    void typesTheCloudProviderLeavesToTheControllerAreTheControllers(String type) {
        EksLoadBalancerSpec spec = spec(service(Map.of("service.beta.kubernetes.io/aws-load-balancer-type", type), null));

        assertEquals(EksLoadBalancerSpec.Kind.CONTROLLER_NLB, spec.kind());
    }

    @Test
    void controllerNlbIsInternetFacingWhenItsSchemeSaysSo() throws Exception {
        EksLoadBalancerSpec spec = spec(service(Map.of("service.beta.kubernetes.io/aws-load-balancer-scheme",
                "internet-facing"), "service.k8s.aws/nlb"));

        assertFalse(spec.internal());
        assertEquals("k8s-teama-webfront-" + sha256(CLUSTER + UID + "internet-facing").substring(0, 10),
                spec.loadBalancerName());
    }

    @Test
    void controllerNlbTakesItsNameAnnotation() {
        EksLoadBalancerSpec spec = spec(service(Map.of("service.beta.kubernetes.io/aws-load-balancer-name", "my-nlb"),
                "service.k8s.aws/nlb"));

        assertEquals("my-nlb", spec.loadBalancerName());
        assertNull(spec.error());
        EksLoadBalancerSpec tooLong = spec(service(Map.of("service.beta.kubernetes.io/aws-load-balancer-name",
                "a-load-balancer-name-longer-than-32"), "service.k8s.aws/nlb"));
        assertEquals("load balancer name cannot be longer than 32 characters", tooLong.error());
    }

    // The cloud provider refuses spec.loadBalancerIP: "LoadBalancerIP cannot be specified for AWS ELB".
    // https://github.com/kubernetes/cloud-provider-aws/blob/3774b6407a490818c9f5cb0ad4fef2ac562fa299/pkg/providers/v1/aws.go#L2465-L2466
    @Test
    void cloudProviderRefusesALoadBalancerIp() {
        ObjectNode service = service(Map.of(), null);
        ((ObjectNode) service.path("spec")).put("loadBalancerIP", "10.0.0.5");

        assertEquals("LoadBalancerIP cannot be specified for AWS ELB", spec(service).error());
    }

    // A default implementation ignores Services whose loadBalancerClass names another; Auto Mode's eks.amazonaws.com/nlb
    // is not emulated. https://kubernetes.io/docs/concepts/services-networking/service/#load-balancer-class
    @ParameterizedTest
    @CsvSource({"eks.amazonaws.com/nlb", "example.com/other"})
    void otherClassesAreLeftAlone(String loadBalancerClass) {
        assertFalse(spec(service(Map.of(), loadBalancerClass)).handled());
    }

    @Test
    void serviceThatIsNotALoadBalancerIsNotHandled() {
        ObjectNode service = service(Map.of(), null);
        ((ObjectNode) service.path("spec")).put("type", "ClusterIP");

        assertFalse(spec(service).handled());
    }

    @Test
    void portsFinalizersAndPolicyAreRead() {
        ObjectNode service = service(Map.of(), null);
        ((ObjectNode) service.path("spec")).put("externalTrafficPolicy", "Local");
        ((ObjectNode) service.path("metadata")).putArray("finalizers").add("service.kubernetes.io/load-balancer-cleanup");

        EksLoadBalancerSpec spec = spec(service);

        assertEquals(List.of(new EksLoadBalancerSpec.Port(80, "TCP", 30080)), spec.ports());
        assertTrue(spec.localTrafficPolicy());
        assertTrue(spec.hasCleanupFinalizer());
        assertEquals("team-a/web-frontend", spec.namespace() + "/" + spec.name());
    }

    @Test
    void chinaRegionsUseTheirOwnDnsSuffix() {
        EksLoadBalancerSpec spec = EksLoadBalancerSpec.from(service(Map.of(), null), CLUSTER, "cn-north-1");

        assertTrue(spec.hostname().endsWith(".cn-north-1.elb.amazonaws.com.cn"), spec.hostname());
    }

    private static EksLoadBalancerSpec spec(JsonNode service) {
        return EksLoadBalancerSpec.from(service, CLUSTER, REGION);
    }

    private static ObjectNode service(Map<String, String> annotations, String loadBalancerClass) {
        ObjectNode service = JSON.createObjectNode();
        ObjectNode metadata = service.putObject("metadata");
        metadata.put("name", "web-frontend");
        metadata.put("namespace", "team-a");
        metadata.put("uid", UID);
        metadata.put("resourceVersion", "42");
        ObjectNode annotationNode = metadata.putObject("annotations");
        annotations.forEach(annotationNode::put);
        ObjectNode spec = service.putObject("spec");
        spec.put("type", "LoadBalancer");
        if (loadBalancerClass != null) {
            spec.put("loadBalancerClass", loadBalancerClass);
        }
        ObjectNode port = spec.putArray("ports").addObject();
        port.put("port", 80);
        port.put("protocol", "TCP");
        port.put("nodePort", 30080);
        return service;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
