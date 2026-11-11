package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsRegions;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What EKS's load balancer controllers make of a Service of type LoadBalancer, as a pure function of the Service: which
 * controller handles it, whether the load balancer is internal, its name, its DNS name and the status ingress the
 * controller writes. With no class and no type annotation the AWS cloud provider makes a Classic Load Balancer; with
 * {@code aws-load-balancer-type: nlb} a Network Load Balancer; with class {@code service.k8s.aws/nlb}, or the types
 * {@code external} and {@code nlb-ip}, the AWS Load Balancer Controller makes one. Other classes, Auto Mode's
 * {@code eks.amazonaws.com/nlb} included, are left alone. The numbers AWS puts in DNS names are opaque; Floci derives
 * them from the Service UID, so a Service keeps its hostname.
 */
record EksLoadBalancerSpec(String uid, String namespace, String name, String resourceVersion, boolean handled,
                           boolean deleting, Kind kind, boolean internal, String loadBalancerName, String hostname,
                           List<Port> ports, boolean localTrafficPolicy, List<String> sourceRanges,
                           List<String> finalizers, JsonNode statusIngress, String hostPortsAnnotation, String error) {

    static final String CLEANUP_FINALIZER = "service.kubernetes.io/load-balancer-cleanup";
    static final String HOST_PORTS_ANNOTATION = "floci.io/load-balancer-host-ports";
    static final String CONTROLLER_CLASS = "service.k8s.aws/nlb";
    private static final String ANNOTATION_PREFIX = "service.beta.kubernetes.io/";
    private static final String INTERNAL = ANNOTATION_PREFIX + "aws-load-balancer-internal";
    private static final String TYPE = ANNOTATION_PREFIX + "aws-load-balancer-type";
    private static final String SCHEME = ANNOTATION_PREFIX + "aws-load-balancer-scheme";
    private static final String NAME = ANNOTATION_PREFIX + "aws-load-balancer-name";
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");

    enum Kind { CLASSIC, NETWORK, CONTROLLER_NLB }

    record Port(int port, String protocol, int nodePort) {
        String key() {
            return protocol + "/" + port;
        }
    }

    static EksLoadBalancerSpec from(JsonNode service, String clusterName, String region) {
        JsonNode metadata = service.path("metadata");
        JsonNode spec = service.path("spec");
        JsonNode annotations = metadata.path("annotations");
        String uid = metadata.path("uid").asText();
        String namespace = metadata.path("namespace").asText("default");
        String name = metadata.path("name").asText();
        String loadBalancerClass = spec.path("loadBalancerClass").asText(null);
        String type = annotations.path(TYPE).asText("");
        boolean controllerType = "external".equals(type) || "nlb-ip".equals(type);
        Kind kind = CONTROLLER_CLASS.equals(loadBalancerClass) || (loadBalancerClass == null && controllerType)
                ? Kind.CONTROLLER_NLB : "nlb".equals(type) ? Kind.NETWORK : Kind.CLASSIC;
        boolean handled = "LoadBalancer".equals(spec.path("type").asText())
                && (loadBalancerClass == null || CONTROLLER_CLASS.equals(loadBalancerClass));
        List<Port> ports = new ArrayList<>();
        for (JsonNode port : spec.path("ports")) {
            ports.add(new Port(port.path("port").asInt(), port.path("protocol").asText("TCP"),
                    port.path("nodePort").asInt(0)));
        }
        List<String> sourceRanges = new ArrayList<>();
        spec.path("loadBalancerSourceRanges").forEach(range -> sourceRanges.add(range.asText()));
        List<String> finalizers = new ArrayList<>();
        metadata.path("finalizers").forEach(finalizer -> finalizers.add(finalizer.asText()));

        boolean internal;
        String loadBalancerName;
        String error = null;
        String suffix = AwsRegions.dnsSuffixFor(region);
        String hostname;
        if (kind == Kind.CONTROLLER_NLB) {
            String scheme = annotations.path(SCHEME).asText("");
            internal = scheme.isEmpty() ? !annotations.path(INTERNAL).asText("true").equals("false")
                    : !"internet-facing".equals(scheme);
            String requested = annotations.path(NAME).asText("");
            if (!requested.isEmpty()) {
                loadBalancerName = requested;
                if (requested.length() > 32) {
                    error = "load balancer name cannot be longer than 32 characters";
                }
            } else {
                String hash = sha256Hex(clusterName + uid + (internal ? "internal" : "internet-facing"));
                loadBalancerName = "k8s-" + truncate(NOT_ALPHANUMERIC.matcher(namespace).replaceAll(""), 8)
                        + "-" + truncate(NOT_ALPHANUMERIC.matcher(name).replaceAll(""), 8) + "-" + hash.substring(0, 10);
            }
            hostname = loadBalancerName + "-" + sha256Hex("nlb/" + uid).substring(0, 16) + ".elb." + region + "." + suffix;
        } else {
            String internalValue = annotations.path(INTERNAL).asText("");
            internal = !internalValue.isEmpty() && !"false".equals(internalValue);
            loadBalancerName = truncate("a" + uid.replace("-", ""), 32);
            if (kind == Kind.NETWORK) {
                hostname = loadBalancerName + "-" + sha256Hex("nlb/" + uid).substring(0, 16) + ".elb." + region + "."
                        + suffix;
            } else {
                hostname = (internal ? "internal-" : "") + loadBalancerName + "-" + digits(uid, 10) + "." + region
                        + ".elb." + suffix;
            }
            if (!spec.path("loadBalancerIP").asText("").isEmpty()) {
                error = "LoadBalancerIP cannot be specified for AWS ELB";
            }
        }
        return new EksLoadBalancerSpec(uid, namespace, name, metadata.path("resourceVersion").asText(null), handled,
                metadata.hasNonNull("deletionTimestamp"), kind, internal, loadBalancerName, hostname, List.copyOf(ports),
                "Local".equals(spec.path("externalTrafficPolicy").asText()), List.copyOf(sourceRanges),
                List.copyOf(finalizers), service.path("status").path("loadBalancer").path("ingress"),
                annotations.path(HOST_PORTS_ANNOTATION).asText(null), error);
    }

    boolean hasCleanupFinalizer() {
        return finalizers.contains(CLEANUP_FINALIZER);
    }

    /**
     * The status ingress the controller writes: the DNS name; the Load Balancer Controller adds the ports, in one
     * entry.
     */
    List<Map<String, Object>> ingress() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("hostname", hostname);
        if (kind == Kind.CONTROLLER_NLB) {
            List<Map<String, Object>> statusPorts = new ArrayList<>();
            for (Port port : ports) {
                Map<String, Object> statusPort = new LinkedHashMap<>();
                statusPort.put("port", port.port());
                statusPort.put("protocol", port.protocol());
                statusPorts.add(statusPort);
            }
            entry.put("ports", statusPorts);
        }
        return List.of(entry);
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static String digits(String seed, int count) {
        String decimal = new BigInteger(1, sha256("clb/" + seed)).toString();
        return decimal.substring(0, count);
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
