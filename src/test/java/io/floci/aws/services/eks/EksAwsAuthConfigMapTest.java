package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node mapping EKS adds to {@code aws-auth} when it creates a managed node group in a cluster without access
 * entries: the node role, {@code system:node:{{EC2PrivateDNSName}}}, and the bootstrappers and nodes groups.
 * https://docs.aws.amazon.com/eks/latest/userguide/auth-configmap.html
 */
class EksAwsAuthConfigMapTest {

    private static final String NODE_ROLE = "arn:aws:iam::000000000000:role/eks-node";

    @Test
    void nodeRoleIsAddedToAnEmptyMapping() {
        String yaml = EksAwsAuthConfigMap.withNodeRole("", NODE_ROLE);

        EksAwsAuthConfigMap.Mapping mapping = mapping(yaml, NODE_ROLE).orElseThrow();
        assertEquals("system:node:{{EC2PrivateDNSName}}", mapping.username());
        assertEquals(List.of("system:bootstrappers", "system:nodes"), mapping.groups());
    }

    @Test
    void nodeRoleIsMergedIntoTheMappingsAlreadyThere() {
        String existing = "- rolearn: arn:aws:iam::000000000000:role/deployer\n  username: deployer\n"
                + "  groups:\n  - system:masters\n";

        String yaml = EksAwsAuthConfigMap.withNodeRole(existing, NODE_ROLE);

        assertTrue(mapping(yaml, "arn:aws:iam::000000000000:role/deployer").isPresent());
        assertTrue(mapping(yaml, NODE_ROLE).isPresent());
    }

    @Test
    void aRoleAlreadyMappedIsLeftAsItIs() {
        String existing = EksAwsAuthConfigMap.withNodeRole("", NODE_ROLE);

        assertNull(EksAwsAuthConfigMap.withNodeRole(existing, NODE_ROLE));
    }

    @Test
    void theCanonicalRoleOfARoleWithAPathIsWhatIsMapped() {
        String yaml = EksAwsAuthConfigMap.withNodeRole("", "arn:aws:iam::000000000000:role/nodes/eks-node");

        assertTrue(mapping(yaml, "arn:aws:iam::000000000000:role/eks-node").isPresent());
    }

    private static Optional<EksAwsAuthConfigMap.Mapping> mapping(String mapRoles, String arn) {
        String json = "{\"data\":{\"mapRoles\":" + new TextNode(mapRoles) + "}}";
        return EksAwsAuthConfigMap.find(json, arn);
    }
}
