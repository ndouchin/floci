package io.floci.aws.services.eks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The appliance's NAT rules and the CoreDNS server block that resolves load balancer names for pods. */
class EksLoadBalancerRuntimeTest {

    @Test
    void applianceSpreadsEachPortOverTheBackendNodePortsAndMasquerades() {
        String script = EksLoadBalancerRuntime.script(new EksLoadBalancerController.Appliances.Spec("lb", "uid",
                List.of(new EksLoadBalancerSpec.Port(80, "TCP", 30080)), List.of("10.250.0.20", "10.250.0.21"),
                List.of()));

        assertTrue(script.contains("-p tcp --dport 80 -m statistic --mode random --probability 0.5000000000"
                + " -j DNAT --to-destination 10.250.0.20:30080"), script);
        assertTrue(script.contains("-p tcp --dport 80 -j DNAT --to-destination 10.250.0.21:30080"), script);
        assertTrue(script.contains("-t nat -A POSTROUTING -p tcp -d 10.250.0.21 --dport 30080 -j MASQUERADE"), script);
        assertTrue(script.endsWith("exec sleep infinity\n"));
    }

    // loadBalancerSourceRanges limit who reaches the load balancer, as klipper-lb does with its FORWARD rules.
    // https://github.com/k3s-io/klipper-lb/blob/b66f14c7fac4a3e6b981e8560a6caaec8bce16cb/entry
    @Test
    void sourceRangesLimitWhoIsForwarded() {
        String script = EksLoadBalancerRuntime.script(new EksLoadBalancerController.Appliances.Spec("lb", "uid",
                List.of(new EksLoadBalancerSpec.Port(443, "TCP", 30443)), List.of("10.250.0.20"),
                List.of("192.0.2.0/24")));

        assertTrue(script.contains("-p tcp -s 192.0.2.0/24 --dport 443 -j DNAT --to-destination 10.250.0.20:30443"),
                script);
    }

    @Test
    void coreDnsServerBlockAnswersBothElbZonesOfTheRegion() {
        Map<String, String> names = new TreeMap<>(Map.of(
                "a1b2-1234567890.us-east-1.elb.amazonaws.com", "10.250.0.130",
                "k8s-default-web-0123456789-0123456789abcdef.elb.us-east-1.amazonaws.com", "10.250.0.131"));

        String block = EksLoadBalancerRuntime.coreDnsServerBlock("us-east-1", names);

        assertEquals("""
                us-east-1.elb.amazonaws.com:53 elb.us-east-1.amazonaws.com:53 {
                    hosts {
                        10.250.0.130 a1b2-1234567890.us-east-1.elb.amazonaws.com
                        10.250.0.131 k8s-default-web-0123456789-0123456789abcdef.elb.us-east-1.amazonaws.com
                        fallthrough
                    }
                    forward . /etc/resolv.conf
                }
                """, block);
    }
}
