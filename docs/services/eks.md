# EKS (Elastic Kubernetes Service)

**Protocol:** REST-JSON  
**Endpoint:** `http://localhost:4566/` (path-routed via JAX-RS)

EKS uses a standard REST API with JSON bodies: not the JSON 1.1 (`X-Amz-Target`) or Query protocol.

## Supported Operations

| Operation | Description |
|---|---|
| `CreateCluster` | Create a new EKS cluster |
| `DescribeCluster` | Describe a cluster by name |
| `ListClusters` | List cluster names with pagination (`maxResults` 1-100 and `nextToken`) |
| `DeleteCluster` | Delete a cluster that has no node groups or Fargate profiles left |
| `CreateAccessEntry` | Create STANDARD or EC2_LINUX access-entry metadata |
| `DescribeAccessEntry` | Describe an access entry by IAM principal ARN |
| `ListAccessEntries` | List principal ARNs with pagination |
| `DeleteAccessEntry` | Delete access-entry metadata |
| `AssociateAccessPolicy` | Associate an EKS access policy and its cluster or namespace scope with a STANDARD access entry |
| `ListAssociatedAccessPolicies` | List the access policies associated with an access entry, with pagination |
| `DisassociateAccessPolicy` | Remove an access policy from an access entry |
| `CreatePodIdentityAssociation` | Create a pod identity association between a service account and IAM role |
| `DescribePodIdentityAssociation` | Describe a pod identity association by association ID |
| `ListPodIdentityAssociations` | List pod identity associations in a cluster with optional filtering and pagination |
| `UpdatePodIdentityAssociation` | Update the IAM role, target role, or session tags for a pod identity association |
| `DeletePodIdentityAssociation` | Delete a pod identity association |
| `CreateAddon` | Create an addon for an EKS cluster |
| `DescribeAddon` | Describe an addon by cluster and addon name |
| `ListAddons` | List addon names installed in a cluster with pagination |
| `UpdateAddon` | Update addon configuration, version, or service account role |
| `UpdateClusterVersion` | Move the cluster's Kubernetes version one minor version up |
| `UpdateNodegroupConfig` | Change a node group's labels, taints, scaling, update, repair or warm pool configuration |
| `UpdateNodegroupVersion` | Move a node group to its cluster's Kubernetes version, or another release or launch template version |
| `DescribeUpdate` | Describe a cluster, node group or addon update |
| `ListUpdates` | List the update IDs of a cluster, node group or addon, with pagination |
| `DeleteAddon` | Delete an addon from a cluster |
| `DescribeAddonVersions` | Describe supported addon versions by Kubernetes version or addon name |
| `ListIdentityProviderConfigs` | List identity provider configs (always empty: they are not implemented) |
| `CreateNodegroup` | Create node group metadata for a cluster |
| `DescribeNodegroup` | Describe a node group by cluster and name |
| `ListNodegroups` | List node group names for a cluster |
| `DeleteNodegroup` | Delete a node group |
| `CreateFargateProfile` | Create Fargate profile metadata for a cluster |
| `DescribeFargateProfile` | Describe a Fargate profile by cluster and name |
| `ListFargateProfiles` | List Fargate profile names for a cluster |
| `DeleteFargateProfile` | Delete a Fargate profile |
| `TagResource` | Add tags to a cluster |
| `UntagResource` | Remove tags from a cluster |
| `ListTagsForResource` | List tags on a cluster |

## Access-entry management

`CreateCluster` accepts `accessConfig.authenticationMode` (`CONFIG_MAP`, `API_AND_CONFIG_MAP`, or `API`) and `bootstrapClusterCreatorAdminPermissions`. The API default is `CONFIG_MAP`; access-entry operations require an ACTIVE cluster created with `API` or `API_AND_CONFIG_MAP`.

The access-entry management operations support `STANDARD` (the default) and `EC2_LINUX`. STANDARD accepts existing IAM users or roles, including principals in another account. EC2_LINUX requires a role in the cluster account and generates `system:node:{{EC2PrivateDNSName}}`; custom usernames and Kubernetes groups are not accepted for node entries. Tags supplied at creation are preserved. Repeating a create with the same client token and normalized parameters returns the existing entry. Listing accepts `maxResults` from 1 to 100 and cluster-specific `nextToken` values.

`policyArn` must be an `arn:<partition>:eks::aws:cluster-access-policy/<name>` ARN in the cluster's partition. The policy name itself is not checked against AWS's list of access policies. The scope `type` is `cluster` (no namespaces) or `namespace` (one or more names; wildcards such as `dev-*` are kept as given, and a repeated name is stored once). Associating a policy that is already associated replaces its scope and keeps its `associatedAt`. Only STANDARD entries accept access policies, and deleting an entry removes its policies. Disassociating a policy the entry does not hold succeeds. `ListAccessEntries` accepts `associatedPolicyArn`.

Access policies are enforced as Kubernetes RBAC in real-mode clusters. The identity of a STANDARD entry carries one extra group, `floci:eks-access-entry:<principal ARN>`, and Floci binds that group to the ClusterRole each policy matches in the [EKS Best Practices Guide](https://docs.aws.amazon.com/eks/latest/best-practices/identity-and-access-management.html) table: `AmazonEKSClusterAdminPolicy` to `cluster-admin`, `AmazonEKSAdminPolicy` to `admin`, `AmazonEKSEditPolicy` to `edit` and `AmazonEKSViewPolicy` to `view`. A cluster scope becomes a ClusterRoleBinding; a namespace scope becomes a RoleBinding in each listed namespace that exists, and a name ending in `*` covers every existing namespace it prefixes. A namespace created after the association gets its RoleBinding the next time a principal holding the policy authenticates (Floci re-checks at most every 15 seconds). The bindings are named `floci-eks-<policy>-<hash>`, labelled `eks.floci.io/access-policy`, and reconciled as a whole when the cluster becomes ACTIVE and after every association, disassociation or deletion of an entry with policies, so a removed policy takes its bindings with it. Other access policies (for example `AmazonEKSAdminViewPolicy`) are stored and listed but grant nothing; Floci logs that once per cluster and policy. EKS evaluates access policies in its own authorizer, so in EKS these permissions appear in no RBAC object; in Floci the bindings are visible with `kubectl get clusterrolebindings,rolebindings -A -l eks.floci.io/access-policy`.

The principal ARN in an access-entry path should be URL-encoded, as the SDKs and the CLI send it. Sent unencoded, a principal whose ARN ends in `/access-policies` (a role named `access-policies`) is read as the access-policies route of the shorter principal before it. A DELETE is a disassociate only when a cluster access policy ARN follows `access-policies`, so an entry with `access-policies` inside its IAM path can always be deleted.

Access entries use EKS storage and retain the IAM principal's stable ID internally. Cluster deletion removes its entries, and a cluster recreated with the same name does not inherit previous entries or pagination tokens.

### Cluster authentication

Once a token is verified (see [Connecting with `kubectl`](#connecting-with-kubectl-native-aws-workflow)), Floci maps the caller's IAM principal to a Kubernetes identity the way EKS's [aws-iam-authenticator](https://github.com/kubernetes-sigs/aws-iam-authenticator/blob/master/pkg/server/server.go) does, or refuses it. A role session counts as its role: the principal is the role ARN, path included, as `aws:PrincipalArn` names it.

`CreateCluster` records the IAM principal that called it as the cluster's creator, internal metadata that `DescribeCluster` does not return. A key IAM does not know, such as `test`, makes the account root (`arn:aws:iam::<account>:root`) the creator. What maps a principal then depends on `accessConfig.authenticationMode`, as the [cluster access guide](https://docs.aws.amazon.com/eks/latest/userguide/grant-k8s-access.html) lists the methods of each mode:

| Mode | Principals mapped |
|---|---|
| `CONFIG_MAP` (API default) | The creator, as `kubernetes-admin` in `system:masters`; the `mapRoles` and `mapUsers` entries of the `kube-system/aws-auth` ConfigMap |
| `API_AND_CONFIG_MAP` | Access entries, then `aws-auth`. An access entry wins for a principal in both, as the [migration guide](https://docs.aws.amazon.com/eks/latest/userguide/migrating-access-entries.html) states |
| `API` | Access entries only |

- **Creator.** In `CONFIG_MAP` the creator's mapping is permanent and appears in no ConfigMap or access entry, as the [best practices guide](https://docs.aws.amazon.com/eks/latest/best-practices/identity-and-access-management.html) describes; the `kubernetes-admin` username is not documented by AWS and is Floci's choice. In the two `API` modes the creator is mapped only through the access entry created when [`bootstrapClusterCreatorAdminPermissions`](https://docs.aws.amazon.com/eks/latest/APIReference/API_CreateAccessConfigRequest.html) is true, its default: a STANDARD entry with `AmazonEKSClusterAdminPolicy` at cluster scope, listed like any other entry and revoked by deleting it. The API reference only says "cluster admin access entry"; the policy name is what EKS associates in practice. `bootstrapClusterCreatorAdminPermissions=false` with `CONFIG_MAP` is rejected with `InvalidParameterException` ("bootstrapClusterCreatorAdminPermissions must be true if cluster authentication mode is set to CONFIG_MAP", the message EKS returns as quoted in [terraform-aws-eks#3026](https://github.com/terraform-aws-modules/terraform-aws-eks/issues/3026)); the best practices guide allows `false` only with `API_AND_CONFIG_MAP` or `API`.
- **Identity.** An access entry gives its `username` (EKS's generated default is the user ARN, or `arn:aws:sts::<account>:assumed-role/<role>/{{SessionName}}` for a role, path removed, per [Create access entries](https://docs.aws.amazon.com/eks/latest/userguide/creating-access-entries.html)) and its `kubernetesGroups`, plus the access-policy group described above. An `aws-auth` entry gives its `username` and `groups`; `mapRoles` matches the role ARN without its path, as the [aws-auth guide](https://docs.aws.amazon.com/eks/latest/userguide/auth-configmap.html) requires, and both lists match case-insensitively. The templates `{{AccountID}}`, `{{SessionName}}` (with `@` replaced by `-`), `{{SessionNameRaw}}`, `{{AccessKeyID}}` and `{{EC2PrivateDNSName}}` are rendered. The TokenReview also carries the authenticator's `uid` (`aws-iam-authenticator:<account>:<userid>`) and `extra` keys (`arn`, `canonicalArn`, `sessionName`, `accessKeyId`, `principalId`).
- **Refusal.** A principal nothing maps gets `401 Unauthorized` from the API server, as do federated-user sessions and a mapping whose template cannot be rendered.
- **Root.** The creator must authenticate with credentials Floci can verify, and the public `test` keys are refused. For a cluster created with such a key, run `aws sts get-session-token` with that key and use the session for `aws eks get-token`: the session acts as the account root. Or create the cluster with the IAM user or role you then use with `kubectl`.
- **Older clusters.** A cluster persisted before Floci recorded creators keeps the earlier mapping: every long-term key and STS session of the cluster's account is `floci:aws-iam` in `system:masters`, and a session of another account is refused. Floci logs this once per cluster; recreate the cluster for the behaviour above.

Floci reads `aws-auth` from the cluster on every token review the API server does not answer from its cache, so an edit applies within the webhook cache TTL (30 seconds).

```bash
# CONFIG_MAP: the creator maps further principals through aws-auth.
kubectl apply -f - <<'YAML'
apiVersion: v1
kind: ConfigMap
metadata: {name: aws-auth, namespace: kube-system}
data:
  mapRoles: |
    - rolearn: arn:aws:iam::000000000000:role/deployer
      username: deployer:{{SessionName}}
      groups: [system:masters]
YAML
# API or API_AND_CONFIG_MAP: grant view on one namespace through an access entry.
aws --endpoint-url http://localhost:4566 eks create-access-entry \
  --cluster-name my-cluster --principal-arn arn:aws:iam::000000000000:user/dev
aws --endpoint-url http://localhost:4566 eks associate-access-policy \
  --cluster-name my-cluster --principal-arn arn:aws:iam::000000000000:user/dev \
  --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy \
  --access-scope type=namespace,namespaces=default
```

### EC2 Linux worker authentication

IMDS instance-profile credentials authenticate through a matching `EC2_LINUX` entry as
`system:node:<private-DNS-name>` with `system:bootstrappers` and `system:nodes` groups,
or, in a `CONFIG_MAP` cluster, through an `aws-auth` `mapRoles` entry whose username uses `{{EC2PrivateDNSName}}`.
The webhook verifies the signed token, cluster account/region/incarnation, stored role ID,
running EC2 instance, and attached instance profile. Missing/deleted entries, recreated roles,
revoked sessions and terminated instances are rejected without falling back to administrator access.
The existing k3s webhook cache can retain a successful authentication for up to 30 seconds.

New cluster webhook configurations carry the target account, region and creation timestamp in the URL path.
Kubernetes client-go replaces server URL query parameters when sending TokenReview requests, so
worker scope must not depend on those parameters.
Recreate older local clusters before using worker authentication; a legacy unscoped webhook
rejects instance credentials. Obtain fresh IMDS credentials after upgrading so the session
includes the stable role ID.

!!! note "Authentication scope"
    Automatic managed-node entries are not created. Updating the authentication mode (`UpdateClusterConfig`), `UpdateAccessEntry`, `ListAccessPolicies`, and entry tag updates remain unimplemented. Native AL2023 images, bootstrap RBAC/CSR approval, CNI and worker networking are separate requirements for registration and Ready.

```bash
aws --endpoint-url http://localhost:4566 eks create-cluster \
  --name local-nodes --role-arn arn:aws:iam::000000000000:role/cluster \
  --resources-vpc-config '{}' \
  --access-config authenticationMode=API,bootstrapClusterCreatorAdminPermissions=false
# The IAM role must already exist.
aws --endpoint-url http://localhost:4566 eks create-access-entry \
  --cluster-name local-nodes --principal-arn arn:aws:iam::000000000000:role/worker \
  --type EC2_LINUX
```

## Pod Identity Associations

Floci supports the EKS Pod Identity association management plane. You can map a Kubernetes `(namespace, serviceAccount)` pair directly to an IAM role without configuring OpenID Connect (OIDC) identity providers or mutating ServiceAccount annotations.

### Management Plane Operations

- **Creation**: `CreatePodIdentityAssociation` associates a Kubernetes service account in a specific namespace with an IAM role ARN. The referenced IAM role must exist in IAM. A cluster cannot have duplicate associations for the same `(namespace, serviceAccount)` pair (rejected with HTTP 409 `ResourceInUseException`). Requests support idempotency via `clientRequestToken`.
- **Retrieval**: `DescribePodIdentityAssociation` retrieves full association details by cluster name and association ID (`a-` followed by 17 alphanumeric characters).
- **Listing**: `ListPodIdentityAssociations` returns summaries of associations for a cluster, with optional filtering by `namespace` and `serviceAccount`, plus pagination using `maxResults` (1-100) and cluster-bound `nextToken`.
- **Updating**: `UpdatePodIdentityAssociation` modifies the associated `roleArn`, `targetRoleArn`, `disableSessionTags`, or `policy`.
- **Deletion**: `DeletePodIdentityAssociation` removes an association and returns its final metadata. Deleting an EKS cluster automatically purges all of its pod identity associations.

### Credential injection at admission

Floci registers a `MutatingWebhookConfiguration` in every real-mode cluster, scoped to pod `CREATE`. When a pod is created, Floci looks up an association matching the pod's namespace and its `spec.serviceAccountName`. A pod that matches nothing is admitted unchanged.

Injection happens only when the `eks-pod-identity-agent` addon is installed on the cluster, which is what real EKS requires too. Install it with `CreateAddon` before expecting pods to be mutated.

A matching pod is patched exactly the way real EKS patches it. Every init container and container gets:

| What | Value |
|---|---|
| `AWS_CONTAINER_CREDENTIALS_FULL_URI` | `http://169.254.170.23/v1/credentials` |
| `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE` | `/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token` |
| Volume mount | `/var/run/secrets/pods.eks.amazonaws.com/serviceaccount`, read-only, from volume `eks-pod-identity-token` |
| `AWS_ENDPOINT_URL` | Floci's endpoint, as a cluster container reaches it (Floci only) |
| `AWS_ENDPOINT_URL_STS` | The same endpoint (Floci only) |

The volume is a projected service account token with audience `pods.eks.amazonaws.com`, `expirationSeconds: 86400` and path `eks-pod-identity-token`. A name the pod already carries (a volume, a mount, or any of the environment variables) is left as the workload set it.

Real EKS sets neither endpoint variable. Floci adds them so the credentials it hands out are used against Floci, as every container Floci launches already is, instead of being sent to AWS, which rejects them. `AWS_ENDPOINT_URL_STS` is set beside the global variable because the AWS SDK for Go v2 builds the STS client of its web identity provider before it reads `AWS_ENDPOINT_URL`. A pod cannot resolve the Docker host name (`host.docker.internal` lives only in the cluster container's `/etc/hosts`) or Floci's hostname (on a custom Docker network k3s replaces the loopback resolver `127.0.0.11` with a public one, so `localhost.floci.io` answers `127.0.0.1`), so the endpoint is the Docker host address and is injected only when that is an IP address, as it is when Floci runs in a container. A container that sets `AWS_ENDPOINT_URL` itself gets no `AWS_ENDPOINT_URL_STS`, which would override its choice for STS. A container with any `envFrom` source gets no endpoint variable, because its values are unknown at admission and an injected `env` entry would take precedence over them; it still gets the credentials variables.

### Credential endpoint

Floci serves pod identity credentials through a link-local relay bound to `169.254.170.23:80` inside each node's container, forwarding requests to Floci's credentials endpoint (`GET /v1/credentials`). Workloads reach this endpoint using the injected `AWS_CONTAINER_CREDENTIALS_FULL_URI` environment variable, passing their projected token in the `Authorization` header (`Authorization: <token>` or `Authorization: Bearer <token>`).

The credentials endpoint performs the following validations:
1. Validates token format and extracts the issuer URL without verifying signatures yet.
2. Identifies the matching EKS cluster by comparing the token issuer URL to cluster OIDC issuer URLs.
3. Validates the token signature against the cluster OIDC public key, confirming the token is unexpired and carries audience `pods.eks.amazonaws.com`.
4. Extracts the subject claim (`system:serviceaccount:<namespace>:<serviceAccount>`) and looks up the pod identity association for the cluster, namespace, and service account.
5. Confirms the associated IAM role still exists under the ARN the association names. A role that was deleted, or recreated under another path, gets `400 AccessDeniedException` and no credentials.
6. Issues temporary session credentials (`ASIA...`) for the associated IAM role and returns them in standard AWS container credentials format (`AccessKeyId`, `SecretAccessKey`, `Token`, `AccountId`, `Expiration`).

#### TLS is required

Kubernetes only accepts an `https` `clientConfig.url` on a `MutatingWebhookConfiguration`, together with a `caBundle` the API server trusts. Floci serves HTTP with the default `floci.tls.enabled=false`, so the webhook is skipped with a warning in that case and pods start unmutated. Set `FLOCI_TLS_ENABLED=true` to get injection; the `caBundle` is then Floci's local CA, the same one served at `GET /_floci/ca.pem`.

The webhook URL uses the hostname containers reach Floci on. When Floci runs natively that is `host.docker.internal`, which the self-signed certificate covers. When Floci itself runs in a container the hostname is its container IP, and the self-signed certificate carries no SAN for it, so the API server rejects the handshake. Pods still start, unmutated. Point `FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE` at a name the certificate covers (add it with `FLOCI_HOSTNAME`) to make the webhook reachable from that setup.

The webhook carries `failurePolicy: Ignore`, and Floci admits the pod unchanged on any internal error, so a webhook that cannot be registered or cannot be reached never prevents a pod from being created. Registration failures log a warning and leave the cluster running.

The manifest is written into the cluster's k3s server manifests directory (`/var/lib/rancher/k3s/server/manifests`) before the container starts, so k3s applies it as the API server comes up.

## Addon management

Floci supports the EKS cluster addon management plane for AWS SDKs and Terraform (`aws_eks_addon` resource).

### Supported operations

- **Creation**: `CreateAddon` creates an addon on an ACTIVE cluster. Supported addons are `vpc-cni`, `coredns`, `kube-proxy`, `eks-pod-identity-agent`, `aws-ebs-csi-driver`, `amazon-cloudwatch-observability`, and the `metrics-server` community add-on. If `addonVersion` is omitted, the default version compatible with the cluster Kubernetes version is resolved automatically. Referenced `serviceAccountRoleArn` must exist in IAM. Idempotency is supported via `clientRequestToken`.
- **Retrieval**: `DescribeAddon` returns the complete addon resource shape, including ARN, cluster name, version, status (`ACTIVE`), health issues, tags, service account role ARN, configuration values, pod identity associations, owner, and publisher.
- **Listing**: `ListAddons` lists installed addon names with pagination (`maxResults` and `nextToken`).
- **Updating**: `UpdateAddon` updates the addon version, configuration values, service account role ARN, or resolve-conflicts strategy. It returns an `Update` tracking object and updates the addon metadata.
- **Update tracking**: `DescribeUpdate` describes the status of an addon update (such as an in-place addon version update queried by Terraform), and `ListUpdates` with `addonName` lists them. Addon updates are `Successful` as soon as they are made.
- **Deletion**: `DeleteAddon` marks the addon as `DELETING` and removes it from the cluster. Deleting a cluster automatically cleans up all associated addons.
- **Supported versions**: `DescribeAddonVersions` queries the addon version catalog with optional filtering by `addonName` and `kubernetesVersion`, supporting pagination. As in AWS, a `kubernetesVersion` filter also limits each version's `compatibilities` to that Kubernetes version. The catalog in `src/main/resources/eks/addon-versions.json` holds the parts of AWS's `DescribeAddonVersions` answer that Floci serves, for every Kubernetes version Floci can run; `make eks-addons-sync` regenerates it from `DescribeAddonVersions` with any AWS account's credentials.

### Metadata recording only

Addons in Floci are recorded metadata only, except `metrics-server` (below). Creating or updating an addon does not install or reconcile Kubernetes DaemonSets, Deployments, or custom resources inside the cluster container.

### Metrics Server

"The Kubernetes Metrics Server ... isn't deployed by default in Amazon EKS clusters" ([Amazon EKS user guide](https://docs.aws.amazon.com/eks/latest/userguide/metrics-server.html)); it is installed as the `metrics-server` [community add-on](https://docs.aws.amazon.com/eks/latest/userguide/community-addons.html) (namespace `kube-system`, no service account or IAM policy) or from the upstream manifest. Floci matches this: k3s deploys its bundled metrics-server unless told not to, and Floci drops a [`.skip` file](https://docs.k3s.io/installation/packaged-components) beside each of its manifests in every new cluster, so `kubectl top` and the `metrics.k8s.io` API are absent. Creating the `metrics-server` add-on removes the skip files and k3s deploys its bundled metrics-server within 15 seconds (its manifest scan interval); deleting the add-on puts them back and deletes what the manifests created, unless `preserve` is set, which in EKS keeps the add-on's software running. The workload is k3s's build of metrics-server, not the EKS community build whose version the add-on reports. Clusters created before this change keep the metrics-server k3s gave them. In mock mode the add-on is metadata only.

The `metrics-server` catalog entry was generated by `tools/aws/regen_eks_addon_versions.py --response` from the unfiltered `aws eks describe-addon-versions --region us-east-1` answer captured on 2026-10-24 by [plumdog/eks-addon-configuration](https://github.com/plumdog/eks-addon-configuration/blob/d5aff59926/data/metrics-server/addon.json) (MIT), since no AWS credentials were available to call the API; `make eks-addons-sync` refreshes it from AWS like the other add-ons.

## Cluster deletion

Deleting a cluster that still has managed node groups or Fargate profiles fails with `ResourceInUseException` (HTTP 409), as the [`DeleteCluster` reference](https://docs.aws.amazon.com/eks/latest/APIReference/API_DeleteCluster.html) requires ("If you have managed node groups or Fargate profiles attached to the cluster, you must delete them first"). The messages are those EKS returns: `Cluster has nodegroups attached` ([hashicorp/terraform#37560](https://github.com/hashicorp/terraform/issues/37560)) and `Cluster has Fargate Profiles in ACTIVE/CREATING state` ([terraform-provider-aws#12179](https://github.com/hashicorp/terraform-provider-aws/issues/12179)). A CloudFormation stack deletes its node groups first, since they reference the cluster.

## Cluster and node group updates

`UpdateClusterVersion`, `UpdateNodegroupConfig` and `UpdateNodegroupVersion` return an [`Update`](https://docs.aws.amazon.com/eks/latest/APIReference/API_Update.html) with status `InProgress`, as EKS does; poll `DescribeUpdate` until it is `Successful` or `Failed`. Repeating a call with the same `clientRequestToken` returns the update the first call started. Metadata-only clusters (mock mode, or no Docker daemon) finish an update as soon as it is recorded. [`ListUpdates`](https://docs.aws.amazon.com/eks/latest/APIReference/API_ListUpdates.html) lists the cluster's own updates, or with `nodegroupName` or `addonName` those of that node group or addon, oldest first, with `maxResults` from 1 to 100 and `nextToken`. `DescribeUpdate` returns an update whether or not the request names its node group or addon, and refuses one that names another. Updates in progress when Floci stops are marked `Failed` at the next start.

### Cluster version

[`UpdateClusterVersion`](https://docs.aws.amazon.com/eks/latest/APIReference/API_UpdateClusterVersion.html) accepts the next minor version only. Any other version, including the current one and older ones, is refused with `InvalidParameterException` and the message EKS returns, `Unsupported Kubernetes minor version update from 1.33 to 1.35` (as reported in [rancher/rancher#41643](https://github.com/rancher/rancher/issues/41643); AWS does not document the message). As the API reference requires, every managed node group must run the cluster's current version first; otherwise the call fails with `InvalidRequestException` and `Nodegroups <names> must be updated to match cluster version <version> before updating cluster version`, the message seen in [terraform-provider-aws#19478](https://github.com/hashicorp/terraform-provider-aws/issues/19478) (the error code is not shown there; `InvalidRequestException` is Floci's reading of "the request is invalid given the state of the cluster"). `force` overrides nothing. A cluster that is not `ACTIVE`, or whose node is being replaced, answers `ResourceInUseException`.

The update reports `Version` and `PlatformVersion` (`eks.1`) parameters, as in the API reference example. The cluster is `UPDATING` while Floci recreates its k3s container on the image of the new version, keeping the named data volume, so workloads and objects survive, and the published API port and certificate authority, so kubeconfigs stay valid. It returns to `ACTIVE` once the new API server answers `/readyz`, with `version` and `platformVersion` updated and the update `Successful`. If the new container cannot start, Floci restores the previous one and the update is `Failed`. Only the server is recreated: the nodes keep running and reconnect to it, and they keep their kubelet version until their node group is updated, as managed nodes do on EKS ([update a cluster](https://docs.aws.amazon.com/eks/latest/userguide/update-cluster.html)); no node work runs while the server is replaced, and nodes may read `NotReady` for a few seconds meanwhile. A [cluster created before managed nodes](#single-shared-node-of-earlier-clusters) has one node, the server's own kubelet, which runs the new version at once.

A cluster created without `version` runs the configured default image (`rancher/k3s:latest`) while reporting 1.29. Recreating it on an older minor than that image runs would downgrade its data, which k3s does not support, so Floci refuses such an update with `InvalidRequestException`; create the cluster with an explicit version to update it.

### Node group configuration

[`UpdateNodegroupConfig`](https://docs.aws.amazon.com/eks/latest/APIReference/API_UpdateNodegroupConfig.html) applies `labels` (`addOrUpdateLabels`, `removeLabels`), `taints` (`addOrUpdateTaints`, `removeTaints`, a taint being identified by its key and effect), `scalingConfig` (only the members sent change), `updateConfig`, `nodeRepairConfig` and `warmPoolConfig`. The update has type `ConfigUpdate` and one parameter per change: `MinSize`, `MaxSize` and `DesiredSize` as in the API reference example, `LabelsToAdd`, `LabelsToRemove`, `TaintsToAdd` and `TaintsToRemove` as JSON (the encoding of these four values is not documented by AWS and is Floci's choice), and `MaxUnavailable`, `MaxUnavailablePercentage`, `UpdateStrategy`, `NodeRepairEnabled` and the `WarmPool*` parameters. The node group is `UPDATING` until it has its new number of nodes, each Ready and carrying the new labels and taints (see [Labels and taints after registration](#labels-and-taints-after-registration)). Floci refuses with `InvalidParameterException` a label both added and removed, a taint without a key or with an effect other than `NO_SCHEDULE`, `NO_EXECUTE` or `PREFER_NO_SCHEDULE`, more than 50 taints, a scaling configuration that breaks `0 <= minSize <= desiredSize <= maxSize` with `maxSize >= 1`, and a request that changes nothing; these messages are Floci's. A node group or cluster that is not `ACTIVE` answers `ResourceInUseException`. A new `desiredSize` launches nodes, or terminates the newest ones without draining them, as EKS scaling ignores PodDisruptionBudgets ([update managed node group](https://docs.aws.amazon.com/eks/latest/userguide/update-managed-node-group.html)); see [Managed nodes](#managed-nodes) for the node cap.

### Node group version

[`UpdateNodegroupVersion`](https://docs.aws.amazon.com/eks/latest/APIReference/API_UpdateNodegroupVersion.html) moves a node group to `version`, by default the cluster's version, and `releaseVersion`, by default the release Floci reports for that version (see [Scalar and collection inputs](#scalar-and-collection-inputs)). A version newer than the cluster's, or older than the node group's ("You cannot roll back a node group to an earlier Kubernetes version"), is refused with `InvalidParameterException`; the EKS user guide now describes rolling back, but the API reference refuses it, and Floci follows the API reference. A `launchTemplate` is accepted only for a node group created with one and must name the same template ID or name, as the API reference requires; its version is resolved in EC2. The update has type `VersionUpdate` with `Version` and `ReleaseVersion` parameters, plus `LaunchTemplateName` and `LaunchTemplateVersion` when sent.

The node group is `UPDATING` while Floci replaces its nodes as EKS's default update strategy does ([managed node update behavior](https://docs.aws.amazon.com/eks/latest/userguide/managed-node-update-behavior.html)): per batch of `updateConfig.maxUnavailable` nodes (1 by default; `maxUnavailablePercentage` of the nodes, at least one), it launches a replacement on the new version (a new instance, so a new ID, address and name), waits for it to be Ready, then labels the old node `node.kubernetes.io/exclude-from-external-load-balancers=true`, cordons it and drains it (`kubectl drain --ignore-daemonsets --delete-emptydir-data --force`), and terminates it. A drain that does not finish within `floci.services.eks.node-drain-timeout-seconds` (default 900, EKS's 15 minutes) fails the update with a `PodEvictionFailure` error and health issue, uncordons the old node and terminates the surplus replacement, unless the update was sent with `force`, which terminates the node anyway. With no free node under the [node cap](#managed-nodes) to launch a replacement first, Floci terminates each node before launching its replacement, as EKS's minimal strategy does, and logs it. The update is `Successful`, and the node group reports its new `version` and `releaseVersion`, once every node runs the new version. A [cluster created before managed nodes](#single-shared-node-of-earlier-clusters) only updates the record and its node's image label, since its node runs the control plane's version.

```bash
aws --endpoint-url http://localhost:4566 eks update-cluster-version --name my-cluster --kubernetes-version 1.34
aws --endpoint-url http://localhost:4566 eks update-nodegroup-config --cluster-name my-cluster \
  --nodegroup-name workers --labels 'addOrUpdateLabels={team=payments}' \
  --taints 'addOrUpdateTaints=[{key=dedicated,value=batch,effect=NO_SCHEDULE}]'
aws --endpoint-url http://localhost:4566 eks list-updates --name my-cluster --nodegroup-name workers
```

## Cluster security group

When an EKS cluster is created with a VPC (either specified directly or resolved from subnets), Floci provisions a dedicated EC2 cluster security group and populates its ID in `cluster.resourcesVpcConfig.clusterSecurityGroupId` on both `CreateCluster` and `DescribeCluster` responses.

### Naming convention and description

The generated security group name follows the AWS pattern:

```
eks-cluster-sg-<cluster-name>-<suffix>
```

where `<suffix>` is an 8-character random hexadecimal string. The security group description is set to:

```
EKS created security group applied to ENI that is attached to EKS Control Plane master nodes, as well as any managed workloads.
```

### System tags

Floci tags the cluster security group with the standard AWS system tags:

- `Name`: `eks-cluster-sg-<cluster-name>-<suffix>`
- `kubernetes.io/cluster/<cluster-name>`: `owned`
- `aws:eks:cluster-name`: `<cluster-name>`

### Lifecycle management and rules

- **Creation**: The security group is created in the resolved cluster VPC when the cluster is created. Both real and mock clusters receive a security group. If a cluster is created without any VPC or subnets, `clusterSecurityGroupId` is omitted from the response. If a caller explicitly provides a `resourcesVpcConfig.vpcId` that does not exist in EC2, the request is rejected with `InvalidParameterException` (HTTP 400).
- **Deletion**: When the cluster is deleted via `DeleteCluster`, Floci deletes the associated cluster security group from EC2. If the security group was already removed out-of-band, the deletion succeeds idempotently without error.
- **Backfill**: Existing persisted clusters that were created before this feature receive an auto-generated cluster security group during startup backfill if their VPC is present.
- **Rules**: In accordance with AWS EKS behavior, the cluster security group includes a self-referencing inbound rule allowing all traffic from members of the same security group to enable control plane and node communication, a self-referencing outbound rule for Elastic Fabric Adapter (EFA) traffic, and the standard EC2 outbound rule.

## Custom control-plane and node arguments (emulator affordance)

Floci allows callers to pass custom flags to k3s server components when creating an EKS cluster via resource tags. This is an emulator affordance with no direct AWS equivalent, intended for testing configurations that require custom kubelet or control plane flags in local environments.

### Tag formats

Custom arguments are supplied as tags in `CreateCluster`:

- Prefixed flag tag: `floci:kubelet-arg:<flag>=<value>` or `floci:kubelet-arg:<flag>` with tag value set to `<value>` (for example, `floci:kubelet-arg:max-pods` = `250`).
- Plural tag: `floci:kubelet-args` with tag value set to a JSON array of flag strings (for example, `["max-pods=250", "image-gc-high-threshold-percent=80"]`) or newline-separated lines.
- Singular tag: `floci:kubelet-arg` with tag value set to `<flag>=<value>`.

The supported component prefixes correspond to k3s server argument flags:
- `kubelet` (`--kubelet-arg`): worker node kubelet settings.
- `kube-apiserver` or `apiserver` (`--kube-apiserver-arg`): Kubernetes API server settings.
- `kube-controller-manager` or `controller-manager` (`--kube-controller-manager-arg`): controller manager settings.
- `kube-scheduler` or `scheduler` (`--kube-scheduler-arg`): scheduler settings.
- `kube-cloud-controller-manager` (`--kube-cloud-controller-manager-arg`): cloud controller manager settings.

### Common examples

- **Kubelet capacity**: `floci:kubelet-arg:max-pods=250` raises the pod ceiling on the local node.
- **Kubelet garbage collection**: `floci:kubelet-args` = `["image-gc-high-threshold-percent=70", "image-gc-low-threshold-percent=50"]`.
- **API server feature gates**: `floci:kube-apiserver-arg:feature-gates=CSIStorageCapacity=true`.
- **Controller manager single-node tuning**: `floci:kube-controller-manager-arg:leader-elect=false`.

### Collision rules and Floci-managed arguments

Floci manages a specific set of flags required for container networking, IAM authentication, audit logging, and topology emulation. Passing a conflicting value or modifier (such as `+=` or `-=`) for any of these flags causes `CreateCluster` to reject the request with `InvalidParameterException` (HTTP 400).

Floci manages the following 17 flags:
- Kubelet:
  - `provider-id`: manages EC2 worker node identity.
  - `node-labels`: manages topology zones, instance types, and nodegroup labels. Nodegroup labels merge directly into this managed argument rather than through the passthrough.
  - `system-reserved`: manages memory and CPU reservations.
  - `kube-reserved`: manages kubelet resource allocations.
  - `eviction-hard`: manages memory pressure thresholds.
  - `register-with-taints`: manages worker node taints configured on managed nodegroups.
- API server:
  - `authentication-token-webhook-config-file`: manages worker IAM authentication.
  - `authentication-token-webhook-version`: token webhook protocol version.
  - `authentication-token-webhook-cache-ttl`: webhook cache duration.
  - `service-account-issuer`: IRSA OIDC discovery issuer URL.
  - `service-account-key-file`: IRSA public signing key.
  - `service-account-signing-key-file`: IRSA private signing key.
  - `audit-policy-file`: EKS control plane audit policy.
  - `audit-log-path`: audit log destination.
  - `audit-log-maxage`: audit log retention days.
  - `audit-log-maxbackup`: audit log file rotation count.
  - `audit-log-maxsize`: audit log rotation size.

### Refused arguments

The following flags are refused outright because they conflict with the k3s embedded SQLite (kine) engine or bypass API server RBAC authorization:
- `storage-backend`
- `etcd-servers`
- `authorization-mode`

### Error handling and tag stripping

- **Malformed arguments**: If an argument tag is blank, contains control characters, or cannot be parsed, Floci logs a warning and proceeds with cluster creation.
- **Tag privacy and immutability**: All `floci:*` tags are stripped from public responses and are not returned by `DescribeCluster` or `ListTagsForResource`. Modification via `TagResource` after creation is rejected with `ValidationException`.

## Encryption and logging configuration

EKS clusters support configuring KMS envelope encryption for secrets and control plane logging export.

### Encryption configuration

`CreateCluster` accepts an `encryptionConfig` array specifying the encryption provider for Kubernetes resources:

```json
{
  "encryptionConfig": [
    {
      "resources": ["secrets"],
      "provider": {
        "keyArn": "arn:aws:kms:us-east-1:000000000000:key/12345678-1234-1234-1234-123456789012"
      }
    }
  ]
}
```

- **Supported resources**: Only `["secrets"]` is supported by AWS EKS. Specifying any other resource or leaving resources empty causes `InvalidParameterException` (HTTP 400).
- **Provider**: `provider.keyArn` must be non-empty.
- **Cardinality**: AWS allows at most one encryption configuration entry. Specifying more than one causes `InvalidParameterException` (HTTP 400).
- **Omission**: When omitted at creation time, `encryptionConfig` is not populated and is omitted from `DescribeCluster` responses (null or omitted, no synthetic default).
- **Scope**: Floci stores and returns the encryption configuration faithfully. Envelope encryption of Secrets at the Kubernetes storage layer and KMS cryptographic operations are out of scope.

### Logging configuration

`CreateCluster` accepts control plane logging configuration in `logging.clusterLogging`:

```json
{
  "logging": {
    "clusterLogging": [
      {
        "types": ["api", "audit"],
        "enabled": true
      }
    ]
  }
}
```

- **Supported log types**: The valid EKS log types are `api`, `audit`, `authenticator`, `controllerManager`, and `scheduler`. Specifying an unrecognized type causes `InvalidParameterException` (HTTP 400).
- **Response normalization**: AWS EKS always returns the status of all five log types. Enabled types are returned first (`enabled: true`), followed by disabled types (`enabled: false`).
- **Default logging**: When omitted or empty at creation time, Floci returns all five log types disabled in a single entry (`enabled: false`).
- **Backfill**: Existing persisted clusters created before this feature was introduced are automatically backfilled on startup with default disabled logging.
- **CloudWatch Logs delivery**:
  - When the `api` log type is enabled on a cluster, Floci creates the CloudWatch Logs log group `/aws/eks/<cluster-name>/cluster` and streams control plane container output to a log stream named `kube-apiserver-<hash>`, where `<hash>` is the first 32 characters of the container ID.
  - When the `audit` log type is enabled on a cluster, Floci configures k3s with the Amazon EKS control plane audit policy, writes audit logs to `/var/log/audit.log` inside the container, and streams audit records to a log stream named `kube-apiserver-audit-<hash>`. Audit records are available in CloudWatch Logs and are not echoed to the Floci console.
  - When both `api` and `audit` are enabled, both streams are created and populated under `/aws/eks/<cluster-name>/cluster`. When neither is enabled, no log group or streams are created.
- **Component streams deviation**: AWS EKS provisions separate log streams for each component (`kube-apiserver-*`, `kube-apiserver-audit-*`, `kube-controller-manager-*`, `kube-scheduler-*`, `authenticator-*`). Because Floci runs clusters on k3s, which embeds the Kubernetes API server, controller manager, and scheduler within a single process, control plane container logs are delivered to the single `kube-apiserver-<hash>` stream when `api` is enabled, and API server audit logs are delivered to `kube-apiserver-audit-<hash>` when `audit` is enabled. Other control plane log types (`authenticator`, `controllerManager`, `scheduler`) do not provision separate streams.
- **Authenticator logs**: Authenticator webhook authentication events are logged directly by Floci.

## Node group inputs

`CreateNodegroup` accepts the full documented input set and stores every member, so `CreateNodegroup` and `DescribeNodegroup` both return exactly what was supplied.

### Scalar and collection inputs

`nodegroupName`, `nodeRole`, `subnets`, `version`, `releaseVersion`, `amiType`, `capacityType`, `diskSize`, `instanceTypes`, `scalingConfig`, `labels`, `tags`, and `clientRequestToken`. Several of these receive an AWS-shaped default when omitted: `capacityType` becomes `ON_DEMAND`, `diskSize` becomes `20` and `instanceTypes` becomes `["t3.medium"]` ([`CreateNodegroup`](https://docs.aws.amazon.com/eks/latest/APIReference/API_CreateNodegroup.html)), and `version` is inherited from the cluster, which is also "the only accepted specified value": another version is refused with `InvalidParameterException`. `amiType` becomes `AL2023_x86_64_STANDARD` from Kubernetes 1.30, since new managed node groups default to Amazon Linux 2023 from that version ([Amazon Linux 2023](https://docs.aws.amazon.com/eks/latest/userguide/al2023.html)), and `AL2_x86_64` before; `ARM_64` variants for Graviton instance types, the `NVIDIA` (1.30 and later) or `AL2_x86_64_GPU` variant for GPU instance types (the GPU default is not documented and is Floci's choice), and `CUSTOM` when the launch template names an AMI. `releaseVersion` takes the EKS AMI release form `<major>.<minor>.<patch>-<date>` ([Amazon Linux AMI versions](https://docs.aws.amazon.com/eks/latest/userguide/eks-linux-ami-versions.html)), the patch being the k3s release Floci runs for that minor and the date a fixed `20251023`, for example `1.33.1-20251023`.

### Structured inputs

`launchTemplate`, `remoteAccess`, `taints`, `updateConfig`, `nodeRepairConfig`, and `warmPoolConfig` are recorded as supplied and echoed back verbatim, including nested members:

```json
{
  "nodegroupName": "my-nodegroup",
  "nodeRole": "arn:aws:iam::000000000000:role/eks-node-role",
  "subnets": ["subnet-123"],
  "launchTemplate": {
    "id": "lt-0a1b2c3d4e5f60718",
    "version": "3"
  },
  "remoteAccess": {
    "ec2SshKey": "my-keypair",
    "sourceSecurityGroups": ["sg-0123456789abcdef0"]
  },
  "taints": [
    {"key": "dedicated", "value": "gpu", "effect": "NO_SCHEDULE"}
  ],
  "nodeRepairConfig": {"enabled": true},
  "warmPoolConfig": {"enabled": true, "minSize": 2, "poolState": "Stopped"}
}
```

- **Omission**: When a structured input is not supplied, it is omitted from `CreateNodegroup` and `DescribeNodegroup` responses rather than serialized as an explicit `null`. The exception is `updateConfig`, which receives the AWS default of `{"maxUnavailable": 1}`.
- **Validation**: When `launchTemplate` is supplied, Floci validates it against EC2. Requests must specify either `id` or `name`, but not both; supplying neither or both is rejected with `InvalidParameterException` (HTTP 400). The template and requested version (defaulting to the template's default version when omitted) must exist in EC2, otherwise the request is rejected with `InvalidParameterException` (HTTP 400). The other structured inputs (`remoteAccess`, `taints`, `nodeRepairConfig`, `warmPoolConfig`) are not validated against external resources.
- **No backfill**: Node groups created before this was supported genuinely had no launch template, taints, remote access, node repair config, or warm pool config, so there is nothing to reconstruct. They continue to omit those members, which is the correct answer for them.

### Launch template user data execution

When a node group specifies a `launchTemplate`, Floci resolves it from EC2 (by ID or name, and version) and runs its user data in each node it launches, as EC2 runs user data on each instance, once the node's agent has started (`docker exec`; the k3s image has busybox `sh`). A launch template without user data runs nothing.

- **Containerd host configuration**: `/etc/containerd/certs.d` in each node is a link to k3s's containerd certs directory, set before the agent starts, so user data that writes registry host configurations there works unchanged and survives a restart of the node ([Registry host configuration](#registry-host-configuration-pull-through-caches-and-custom-mirrors)).
- **Timing and tradeoffs**: because k3s is already running when the script runs, settings that need a kubelet restart (such as `/etc/rancher/k3s/config.yaml`) take no effect.
- **Failure handling**: a script that exits with a non-zero status or times out (30-minute limit) keeps the node from counting as launched, and the node group becomes `CREATE_FAILED` with a `NodeCreationFailure` issue carrying the failure message.
- **Differences from EC2**: other launch template settings (instance type, block device mappings, network interfaces) remain metadata; the AMI only sets the `nodegroup-image` label, and `PrivateDnsNameOptions.HostnameType` the node's name.

### Nodegroup labels, taints and capacity type

Each node registers with its node group's labels and taints (k3s `--node-label` and `--node-taint`), and Floci keeps them current through the Kubernetes API afterwards.

#### Applied labels
- Topology: `topology.kubernetes.io/region`, `topology.kubernetes.io/zone` (the zone of the node's subnet) and `topology.k8s.aws/zone-id` (its zone ID, which the AWS cloud controller manager sets, [`well_known_labels.go`](https://github.com/kubernetes/cloud-provider-aws/blob/3774b6407a490818c9f5cb0ad4fef2ac562fa299/pkg/providers/v1/well_known_labels.go#L19-L27)).
- Instance type: `node.kubernetes.io/instance-type`, and `beta.kubernetes.io/instance-type` with the same value, from the node group's first `instanceTypes` entry, whatever the catalog holds.
- EKS labels, as listed in [Target AI/ML workloads to specific nodes with labels](https://docs.aws.amazon.com/eks/latest/userguide/ml-workload-placement.html):
  - `eks.amazonaws.com/nodegroup=<nodegroupName>`
  - `eks.amazonaws.com/capacityType=<ON_DEMAND|SPOT|CAPACITY_BLOCK>` (`ON_DEMAND` when omitted)
  - `eks.amazonaws.com/nodegroup-image=<AMI ID>`: the launch template's image when it names one, else the EKS optimized AMI of the AMI type and version that Floci's EC2 image catalog publishes under `/aws/service/eks/optimized-ami/<version>/...` ([retrieve an AMI ID](https://docs.aws.amazon.com/eks/latest/userguide/retrieve-ami-id.html)). The catalog publishes Amazon Linux 2023 and Amazon Linux 2 AMIs; any other AMI type, and Amazon Linux 2 past 1.32, take the Amazon Linux 2023 AMI of the same architecture.
  - `eks.amazonaws.com/sourceLaunchTemplateId` and `eks.amazonaws.com/sourceLaunchTemplateVersion`, only for a node group created with a launch template.
- User-defined labels: the node group's `labels`, excluding the keys EKS keeps (`topology.kubernetes.io/*`, `eks.amazonaws.com/*`, `node.kubernetes.io/instance-type`). A label in the `kubernetes.io` or `k8s.io` namespaces that [NodeRestriction](https://kubernetes.io/docs/reference/access-authn-authz/admission-controllers/#noderestriction) does not let a kubelet set on itself (for example `node-role.kubernetes.io/worker`) goes on through the API once the node registers.

#### Applied taints
Each taint is registered as `key=value:effect` (or `key:effect` without a value), with the API effects mapped to Kubernetes casing: `NO_SCHEDULE` to `NoSchedule`, `NO_EXECUTE` to `NoExecute`, `PREFER_NO_SCHEDULE` to `PreferNoSchedule`. EKS registers a managed node group's taints when the node joins ([node taints](https://docs.aws.amazon.com/eks/latest/userguide/node-taints-managed-node-groups.html)).

#### Labels and taints after registration
`UpdateNodegroupConfig` changes the labels and taints of every running node through the API server, and nodes launched afterwards register with the new ones. Floci records the keys it manages in the node annotations `floci.io/eks-nodegroup-labels` and `floci.io/eks-nodegroup-taints`, so it removes only the labels and taints it put there and keeps those a workload or an operator added. The zone, region and instance-type labels are overwritten but never removed. A taint is identified by its key and effect, as Kubernetes identifies it.

### Metadata only inputs

The remaining structured inputs are recorded as metadata:

- `remoteAccess` does not open SSH access, and the referenced key pair and security groups are not wired up.
- `nodeRepairConfig` starts no repair loop, and `warmPoolConfig` pre-initializes no instances.

The value of storing them is state fidelity: infrastructure tools read `DescribeNodegroup` back and compare it against their declared configuration. Dropping `launchTemplate` in particular made the OpenTofu AWS provider see a node group with no launch template where one was declared, which is a `ForceNew` difference and caused a destroy and recreate on every plan.

## Modes

### Mock mode (`mock: true`)

Cluster metadata is stored in-process. No Docker containers are started. The cluster transitions directly to `ACTIVE` on creation. Use this in CI or whenever you only need the EKS API shape, not a real Kubernetes API server.

### Real mode (`mock: false`, default)

Floci starts a **k3s** (`rancher/k3s`) container for each cluster. The k3s API server is exposed on a host port from the configured range (`6500-6599`). Once `/readyz` responds, the cluster transitions to `ACTIVE` and the CA certificate is extracted from the kubeconfig.

By default `describe-cluster` returns a **host-reachable** endpoint (`https://localhost:<hostPort>`); the k3s server certificate includes a `localhost` SAN, so it verifies against the CA in `cluster.certificateAuthority.data`. Set `endpoint-mode: network` to return the container DNS name (`https://floci-aws-eks-<name>:6443`) instead: reachable from other containers on the Docker network (the pre-#1118 behaviour). Set `endpoint-host` to advertise a hostname other than `localhost` in `host` mode, for example when `kubectl` runs on another machine; Floci adds it to the k3s certificate too. It takes a DNS name (letters, digits, hyphens and dots), a dotted IPv4 address, or an IPv6 address with or without brackets, without scheme, port or path; anything else stops Floci at startup with an error naming the setting, whenever EKS runs real clusters (even in `network` mode). The certificate is fixed when the k3s container is created, so when `endpoint-host` changes, Floci recreates a surviving container on its next restart (keeping its data volume and port). If that replacement fails, the old container is kept and keeps advertising the host its certificate carries. `network` mode ignores it when Floci runs in a container, and metadata-only clusters (mock mode, or no Docker daemon) do not use it. In `network` mode the endpoint falls back to the host-reachable form when Floci runs natively, since there is no container DNS name a host client could use.

#### Connecting with `kubectl` (native AWS workflow)

The standard AWS flow works end to end:

```bash
aws eks update-kubeconfig --name my-cluster
kubectl get nodes
```

`aws eks update-kubeconfig` wires `aws eks get-token` into the kubeconfig as an exec credential. The bearer token contains a SigV4-presigned STS `GetCallerIdentity` request. Floci validates its signature and 15-minute token lifetime matching `aws-iam-authenticator` rather than the presigned expiry, then verifies the signed `x-k8s-aws-id` header against the cluster-specific `/_floci/eks/clusters/<cluster-name>/token-webhook` endpoint before mapping the caller's IAM principal as [Cluster authentication](#cluster-authentication) describes: the cluster creator, access entries and the `aws-auth` ConfigMap decide who gets in, and anyone else gets `401`. No `aws-iam-authenticator` is required.

Use IAM credentials Floci knows for EKS authentication: an IAM access key, or STS session credentials. The public local-development pairs `test`/`test` and `floci`/`floci` are deliberately rejected, since anyone can sign with them.

Temporary IAM credentials must include their `AWS_SESSION_TOKEN` when signing the token.

This webhook is enabled by default (`iam-auth-webhook: true`). Set it to `false` to start k3s without it (in which case `aws eks get-token` tokens are rejected with `401`).

!!! note "Webhook reachability & networking"
    The k3s API server must be able to reach Floci's webhook URL. When Floci runs natively, k3s containers reach it via `host.docker.internal`; when Floci runs in a container (`floci start`), Floci and the k3s containers share a Docker network. The k3s network is taken from `FLOCI_SERVICES_EKS_DOCKER_NETWORK` if set, otherwise the global `FLOCI_SERVICES_DOCKER_NETWORK`, otherwise the network Floci is itself attached to (auto-detected), so no EKS-specific network configuration is required in the standard compose setup.

    The webhook kubeconfig is copied into the k3s container via the Docker API (not bind-mounted), so the token-webhook works the same in native and Docker-in-Docker modes with **no host-path / `host-persistent-path` configuration**.

!!! note "Docker socket required"
    Real mode starts privileged Docker containers. Mount the Docker socket and set the Docker network so containers can reach each other.

```yaml
services:
  floci:
    image: floci/floci:latest
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    ports:
      - "4566:4566"
    environment:
      FLOCI_SERVICES_EKS_DOCKER_NETWORK: my_project_default
```

!!! note "No port mapping needed for k3s ports"
    k3s containers bind their API server port (6500-6599) directly on the host via Docker: no `ports:` entry is required in `docker-compose.yml`. See [Ports Reference](../configuration/ports.md#ports-65006599-eks-real-mode) for the full explanation.

#### Clusters survive a restart

With a persistent [storage mode](../configuration/storage.md) (the default), clusters recorded in
`eks-clusters.json` are **re-latched to their k3s containers when Floci starts**:

- A surviving container (for example after a Docker Desktop / daemon reboot) is adopted and
  started in place, keeping its published API server port and data volume: deployments come
  back as they were.
- A missing container is recreated. Its named k3s data volume (`floci-aws-eks-<name>`; for a
  [non-default account](../configuration/multi-account.md), `floci-aws-eks-<account>.<name>`) is
  reused if it survived; volumes follow the global prune policy
  (`FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE`, default `false`), so they are retained when the
  container is stopped or the cluster deleted, except in `memory` storage mode.
- A non-default-account cluster created before account-qualified naming keeps its historical
  `floci-eks-<name>` container and volume: restoration adopts the surviving container when its
  `io.floci.account` label matches the owning account, so pre-upgrade workloads are not
  orphaned.

A restored cluster reports `CREATING` until its API server answers again, then returns to
`ACTIVE` with a freshly extracted certificate authority. If the container cannot be brought
back (for example Docker is unavailable), the cluster is marked `FAILED` instead of appearing
`ACTIVE` while unreachable.

#### Managed nodes

A cluster runs as an agentless k3s server, the managed control plane, plus one k3s agent container per managed node group node. The server runs no kubelet (`--disable-agent`), so `kubectl get nodes` lists only managed nodes, as on EKS, where control plane hosts never appear as nodes ([EKS architecture](https://docs.aws.amazon.com/eks/latest/userguide/eks-architecture.html)). A cluster with no node group therefore has no node: CoreDNS and every pod stay `Pending` until a node group gives the cluster one (Fargate profiles do not, see below). The server reaches kubelets and pods through the agents' tunnels (`--egress-selector-mode=cluster`), so `kubectl logs`, `exec`, `port-forward` and `kubectl top` work. It enables the `NodeRestriction` and `ExtendedResourceToleration` admission plugins, both in EKS's enabled list ([platform versions](https://docs.aws.amazon.com/eks/latest/userguide/platform-versions.html)).

**Networks.** The server and its agents share a Docker network of the cluster's own (`floci-aws-eks-net.<cluster>`, on a free `10.250.X.0/24`), where the server holds `.10`, which it advertises and agents join it at, and each node a fixed address from `.20`. Flannel carries pod traffic over this network whatever other networks a node joins. When the node group's subnet is a Docker-backed EC2 subnet (VPC networks are on by default), the node also joins the VPC's Docker network at an address leased from that subnet, and that address is its `InternalIP`, the private IP of its EC2 instance and the source of its name, as on EKS (nodeadm passes the instance's private IPv4 as `--node-ip`, [`config.go`](https://github.com/awslabs/amazon-eks-ami/blob/cb447b971ee8dcf121c4f12887455ca21590ff5d/nodeadm/internal/kubelet/config.go#L406-L428)). A subnet EC2 does not know, or VPC networks turned off, gives the node its cluster network address instead. When Floci runs in a container, nodes also join its network, so they reach Floci's ECR mirror, IMDS relay and DNS.

**Names and identity.** A node is named after its EC2 private DNS name, the IP name EC2 gives by default: `ip-10-0-1-23.ec2.internal` in `us-east-1`, `ip-10-0-1-23.<region>.compute.internal` elsewhere; a launch template whose `PrivateDnsNameOptions.HostnameType` is `resource-name` names it after its instance ID instead ([EC2 hostname types](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/hostname-types.html), [nodeadm `config.go`](https://github.com/awslabs/amazon-eks-ami/blob/cb447b971ee8dcf121c4f12887455ca21590ff5d/nodeadm/internal/kubelet/config.go#L275-L290)). Its instance ID (`i-` and 17 hex digits) is derived from the node group and a launch counter, so a replacement node gets a new ID, address and name, as an EC2 replacement does. Its `spec.providerID` is `aws:///<availability-zone>/<instance-id>` ([`getProviderId`](https://github.com/awslabs/amazon-eks-ami/blob/cb447b971ee8dcf121c4f12887455ca21590ff5d/nodeadm/internal/kubelet/config.go#L401-L403)), the zone being that of its subnet. New nodes go to the node group's subnet holding the fewest of its nodes, in request order; a scale-in terminates the newest node of the zone holding the most (how EKS's Auto Scaling group balances and picks is not verified here). EC2 `DescribeInstances` lists each node as a running instance with the node's ID, private IP, DNS name, subnet, AMI and the tag `aws:eks:cluster-name` ([EKS tags](https://docs.aws.amazon.com/eks/latest/userguide/eks-using-tags.html)); its `eks:cluster-name`, `eks:nodegroup-name`, `aws:autoscaling:groupName` and `kubernetes.io/cluster/<name>` tags follow what managed node instances are seen to carry (not verified), and its instance profile is synthesized for the node group. Floci's DNS answers the node's name, and its DNS queries are attributed to its VPC for Route 53 Resolver rules.

**Pod capacity.** A node holds `ENIs * (IPv4 addresses per ENI - 1) + 2` pods for its instance type ([`eni_max_pods.go`](https://github.com/awslabs/amazon-eks-ami/blob/cb447b971ee8dcf121c4f12887455ca21590ff5d/nodeadm/internal/kubelet/eni_max_pods.go#L63-L79)), capped at 110 under 30 vCPUs and 250 above, as EKS caps managed node groups without a custom AMI ([choosing an instance type](https://docs.aws.amazon.com/eks/latest/userguide/choosing-instance-type.html)); with several instance types, the smallest applies to every node ([prefix delegation](https://docs.aws.amazon.com/eks/latest/userguide/cni-increase-ip-addresses.html)); an instance type the catalog lacks gets 110. It is passed as the kubelet's `max-pods`, so `status.capacity.pods` reads, for example, 17 on a `t3.medium` and 29 on an `m5.large`. The catalog carries the ENI figures of the EKS AMI's [`instance-info.jsonl`](https://github.com/awslabs/amazon-eks-ami/blob/cb447b971ee8dcf121c4f12887455ca21590ff5d/nodeadm/internal/kubelet/instance-info.jsonl) for the common EKS and GPU types it adds (`t3.large`, `m5.xlarge`, `c5.large`, `m6i.large`, `g4dn.xlarge`, `g5.xlarge`, `g5.12xlarge`, `g6.xlarge` and others).

**GPUs.** A node of a GPU instance type advertises its GPUs as `nvidia.com/gpu` capacity and allocatable, from the EC2 catalog's GPU count ([accelerated computing instances](https://docs.aws.amazon.com/ec2/latest/instancetypes/ac.html)), which `DescribeInstanceTypes` now reports as `GpuInfo` too. This is a deviation: EKS's AL2023 NVIDIA AMIs do not include the NVIDIA device plugin, so on EKS the resource appears only once the user installs it ([accelerated AMIs](https://docs.aws.amazon.com/eks/latest/userguide/ml-eks-optimized-ami.html)); a device plugin cannot run without a GPU, so Floci sets the capacity on the node status itself, on every GPU instance type. EKS adds no taint to GPU nodes; a node group's own `nvidia.com/gpu` taint is tolerated automatically by pods that request a GPU, through `ExtendedResourceToleration`. No GPU is attached: workloads that need CUDA do not run.

**Node cap.** Floci runs at most `floci.services.eks.max-nodes-per-cluster` agents per cluster (default 5, `0` for no cap), giving them to node groups in creation order (EKS has no system node group). Nodes past the cap keep their record, and the API reports every node group's requested size, but they have no agent, no Node object and no EC2 instance; Floci logs one warning per change naming the node groups short of nodes. Raising the cap, or removing other nodes, starts them.

**Lifecycle and statuses.** A node reconciler brings each cluster's agents in line with its node groups, one pass at a time per cluster, when a node group is created, updated or deleted, when the cluster becomes `ACTIVE`, and every 30 seconds, which recreates an agent that died (on its volume, under the same name) and removes agents no node has. A node group is `CREATING` until each of its nodes under the cap is Ready, runs its version and has no `node.cloudprovider.kubernetes.io/uninitialized` taint, then `ACTIVE`. Past `floci.services.eks.node-ready-timeout-seconds` (default 900, EKS's 15 minutes, [troubleshooting](https://docs.aws.amazon.com/eks/latest/userguide/troubleshooting.html)) it is `CREATE_FAILED` with a `NodeCreationFailure` issue, or `AsgInstanceLaunchFailures` when Docker could not create a node's container ([`Issue`](https://docs.aws.amazon.com/eks/latest/APIReference/API_Issue.html)). An `ACTIVE` node group whose node cannot be launched again is `DEGRADED` with that issue until it can (when EKS sets `DEGRADED` is not documented; this is Floci's mapping). `DeleteNodegroup` returns the node group `DELETING`, and its record goes once its agents, Node objects and VPC leases are gone; `DeleteCluster` keeps refusing while a node group is listed, `DELETING` ones included. CloudFormation waits for a node group to be `ACTIVE` after create and gone after delete. Mock mode and clusters without Docker keep creating and deleting node groups at once.

**Restore.** In a persistent storage mode, a restarted Floci re-claims the VPC addresses of stored nodes, adopts the server, then adopts each node's agent by name and specification label (starting it if stopped), recreates a missing one on its volume with the same identity (its node password, derived from the join token and its name, lets it rejoin under that name), and removes agents no node has. Memory mode restores nothing.

**Clusters created before managed nodes.** A cluster stored by an earlier Floci has no topology recorded. It is adopted as the single-node cluster it was, and keeps working as before: one k3s container that is also the node, named `i-<hash>.<domain>`, carrying the first node group's labels and taints, sized from its instance type, and running launch template user data once ([Single shared node](#single-shared-node-of-earlier-clusters)). Nothing migrates it; recreate the cluster to get managed nodes.

**Fargate.** Fargate profiles stay metadata: pods a Fargate profile would run get no per-pod node and stay `Pending` unless a node group's node takes them.

**Not emulated.** Node repair, warm pools and Spot interruptions; scale-in does not drain, as EKS scaling ignores PodDisruptionBudgets ([update managed node group](https://docs.aws.amazon.com/eks/latest/userguide/update-managed-node-group.html)). The IMDS and pod identity relays run in each node as they ran in the single node, and need `socat` and `curl`, which the stock k3s image lacks, so on stock images they log a warning and do not run. `local-path` volumes (`default-storage-class: true`) live on one node and are lost to their pods when that node is replaced.

#### Cluster node capacity

Each node's container receives a hard CPU quota equal to its instance type's vCPUs and a memory limit equal to its catalog memory plus 10% headroom (at least 128 MiB), both capped by Docker's host capacity, memory at 80% of the Docker host's memory. The server has no limit. `floci.services.eks.max-memory-mib` and `floci.services.eks.max-vcpus` provide optional lower ceilings per node; zero means no extra ceiling (`FLOCI_SERVICES_EKS_MAX_MEMORY_MIB`, `FLOCI_SERVICES_EKS_MAX_VCPUS`). An instance type absent from the catalog is sized as `m5.large`, with a warning, while the node keeps its requested type's `node.kubernetes.io/instance-type` label.

Those ceilings can only lower a node. To give workloads more room locally than the node group asks for on AWS, set `floci.services.eks.node-instance-type` (`FLOCI_SERVICES_EKS_NODE_INSTANCE_TYPE`) to a catalog type such as `t2.2xlarge`: node containers and their kubelet reservations are then sized from it, while `DescribeNodegroup`, the nodes' instance-type label and their EC2 instances keep the requested type, so the same Terraform plans no changes. A type absent from the catalog is ignored with a warning. This setting is Floci-only.

The same calculation sets each kubelet's `kube-reserved`, `system-reserved` and `eviction-hard` flags. The EKS AMI reserves `255 + 11 * maxPods` MiB and a tiered CPU fraction for kubelet and the container runtime, `maxPods` being the node's pod capacity above. Floci's `system-reserved` memory holds the container headroom, and its CPU reservation excludes the Docker host CPUs outside the container quota. Its absolute `memory.available` threshold includes the difference between Docker host memory and the container limit, plus a 100 MiB eviction buffer, because kubelet in the nested container can report host memory while the kernel enforces the smaller cgroup limit. The node filesystem eviction defaults remain at 10% available space and 5% free inodes. The formulas follow the [EKS AMI nodeadm source](https://github.com/awslabs/amazon-eks-ami/blob/cb447b971ee8dcf121c4f12887455ca21590ff5d/nodeadm/internal/kubelet/config.go#L293-L307) and [AWS's node memory calculation](https://docs.aws.amazon.com/batch/latest/userguide/memory-cpu-batch-eks.html). If Docker cannot report host capacity, or the host is too small to leave 256 MiB for pods after reservations, Floci warns and launches the node without the derived limits or kubelet arguments; an explicit ceiling remains a hard bound. A change of these inputs recreates the node's agent on its volume.

#### Single shared node of earlier clusters

A cluster stored before managed nodes (no topology recorded) keeps one k3s container that is both control plane and node, as Floci ran every cluster before. Its node is named after a synthesized EC2 instance with resource-based naming (`i-<hash>.ec2.internal`), with the provider ID `aws:///<region>a/<instance-id>`, and carries the labels and taints of its first active node group (by `createdAt`, then name); later node groups share it and log a warning. The first node group selects the node's instance type and recreates the container if it changes, keeping its volume and port; launch template user data runs once in it; `desiredSize` adds no node; `UpdateClusterVersion` moves its kubelet with the control plane. A surviving container whose capacity label differs from the current calculation is recreated against the retained data volume on restore, keeping the old one stopped (`floci-aws-eks-capacity-backup.<cluster>`) until the replacement starts.

#### LoadBalancer Services

A cluster created with `floci.services.eks.load-balancer.mode: emulated` (the default) runs k3s without its servicelb and gives each Service of type `LoadBalancer` what EKS's load balancer controllers give it: a load balancer DNS name in `status.loadBalancer.ingress`, the `service.kubernetes.io/load-balancer-cleanup` finalizer, added before anything else, and the service controller's events (`EnsuringLoadBalancer`, `EnsuredLoadBalancer`, `UpdatedLoadBalancer`, `UnAvailableLoadBalancer`, `SyncLoadBalancerFailed`, `DeletingLoadBalancer`, `DeletedLoadBalancer`), as kubernetes/cloud-provider's service controller does ([`controller.go`](https://github.com/kubernetes/cloud-provider/blob/6d61da6ed70a044418fb1de39b8d440c015b683c/controllers/service/controller.go#L863-L962), [`events.go`](https://github.com/kubernetes/cloud-provider/blob/057af21b6daed5fe33ca56d83c2b1c74941965ca/events/events.go#L25-L49)).

| Service | Load balancer, as EKS makes it | Hostname written to the status |
|---|---|---|
| no `loadBalancerClass`, no type annotation | Classic Load Balancer of the AWS cloud provider, named `a` + the UID without dashes, 32 characters ([`aws.go`](https://github.com/kubernetes/cloud-provider-aws/blob/3774b6407a490818c9f5cb0ad4fef2ac562fa299/pkg/providers/v1/aws.go#L2867-L2906), [EKS network load balancing](https://docs.aws.amazon.com/eks/latest/userguide/network-load-balancing.html)) | `a<uid32>-<10 digits>.<region>.elb.amazonaws.com`, prefixed `internal-` when `service.beta.kubernetes.io/aws-load-balancer-internal` is set and not `false` ([internet-facing](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-internet-facing-load-balancers.html), [internal](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-internal-load-balancers.html)) |
| `service.beta.kubernetes.io/aws-load-balancer-type: nlb` | Network Load Balancer of the cloud provider, named `a<uid32>` | `a<uid32>-<16 hex>.elb.<region>.amazonaws.com` ([NLB DNS names](https://docs.aws.amazon.com/elasticloadbalancing/latest/network/network-load-balancers.html)) |
| `loadBalancerClass: service.k8s.aws/nlb`, or type `external` or `nlb-ip` | Network Load Balancer of the AWS Load Balancer Controller, named `k8s-<namespace, 8>-<name, 8>-<10 hex of sha256(cluster, UID, scheme)>` or `service.beta.kubernetes.io/aws-load-balancer-name`, internal unless `service.beta.kubernetes.io/aws-load-balancer-scheme: internet-facing` ([`model_build_load_balancer.go`](https://github.com/kubernetes-sigs/aws-load-balancer-controller/blob/fdcdb1e5864e1603083f92ef642ee12b19568e99/pkg/service/model_build_load_balancer.go#L577-L595), [LBC NLB guide](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/service/nlb/)) | `<name>-<16 hex>.elb.<region>.amazonaws.com`, with the ports in the same entry ([`service_controller.go`](https://github.com/kubernetes-sigs/aws-load-balancer-controller/blob/fdcdb1e5864e1603083f92ef642ee12b19568e99/controllers/service/service_controller.go#L252-L316)) |
| any other class, Auto Mode's `eks.amazonaws.com/nlb` included | left alone | none |

The digits and hex AWS puts in DNS names are opaque; Floci derives them from the Service UID, so a Service keeps its name across restarts. The domain follows the region's partition (`amazonaws.com.cn` in China). Floci behaves as if the Load Balancer Controller were installed, without its mutating webhook, so a Service without a class gets the Classic Load Balancer. `spec.loadBalancerIP` is refused as the cloud provider refuses it (`LoadBalancerIP cannot be specified for AWS ELB`, a `SyncLoadBalancerFailed` event, no status), and so is a Load Balancer Controller name longer than 32 characters.

**Reaching it.** Each load balancer is an appliance container from the cluster's own k3s image, on the cluster network, that forwards each Service port to the NodePort of every Ready node Floci runs for the cluster (not labelled `node.kubernetes.io/exclude-from-external-load-balancers`; with `externalTrafficPolicy: Local`, only nodes with a ready endpoint), spread at random, masquerading the source; `loadBalancerSourceRanges` limit who is forwarded. It runs with `NET_ADMIN` and `net.ipv4.ip_forward=1`, not privileged.

| From | How |
|---|---|
| the Docker host (macOS or Linux) | `127.0.0.1:<port>` from the Service's `floci.io/load-balancer-host-ports` annotation, for example `TCP/80=127.0.0.1:41234` (`floci.services.eks.load-balancer.host-ports`: `dynamic` by default, `same-as-service` for the Service's own port when free, or `none`); a replaced appliance asks for the same ports again |
| a pod | the DNS name: Floci writes a server block for the region's ELB zones into the `coredns-custom` ConfigMap (key `floci-load-balancers.server`), which k3s's CoreDNS imports, so pods resolve the name to the appliance within about a minute; keys you put in `coredns-custom` stay |
| a container on Floci's network, or on the cluster network | the DNS name, which Floci's embedded DNS answers with the appliance's address (on Floci's network when Floci runs in a container), or the appliance's address |

**Lifecycle.** The controller passes over each running cluster every 3 seconds; a change of ports, backends or the image recreates the appliance. Deleting the Service, or changing its type, removes the appliance and its name, then the finalizer. Deleting the cluster removes every appliance. `floci.services.eks.load-balancer.provisioning-delay-seconds` (default 0) holds the hostname back for a while, as cloud load balancers take a few minutes ([EKS network load balancing](https://docs.aws.amazon.com/eks/latest/userguide/network-load-balancing.html) gives no figure).

**Modes.** `servicelb` keeps k3s's servicelb, which writes node addresses to the status as Floci did before; `off` leaves Services pending. The mode is recorded on each cluster when it is created (and as the server's `io.floci.load-balancer-mode` label); a [cluster created before managed nodes](#single-shared-node-of-earlier-clusters) keeps servicelb.

**Not emulated.** No ELB or ELBv2 record is created in Floci's own APIs (`DescribeLoadBalancers` does not list these load balancers); health checks, client IP preservation, UDP host ports (UDP Services get a status and in-cluster forwarding, but no host port), IP targets (`nlb-ip` and `allocateLoadBalancerNodePorts: false` get a status only, since the appliance reaches NodePorts, not pods), and IPv6. With `disable-cni: true`, kube-proxy is off and NodePorts work only if the CNI you install implements them.

#### Storage classes

Stock Amazon EKS clusters historically define a default `gp2` StorageClass pointing to the legacy in-tree `kubernetes.io/aws-ebs` plugin, but run no active storage provisioner without the EBS CSI driver. Unqualified PersistentVolumeClaims remain `Pending` on EKS because nothing can provision them. Floci matches this behavior by disabling k3s's bundled `local-path` provisioner and StorageClass, preventing unqualified claims from silently binding to the node filesystem.

Callers requiring dynamic volume provisioning can install a driver (such as the `aws-ebs-csi-driver` addon) and define their desired StorageClass.

For local testing workflows that rely on automatic volume binding without a CSI driver, set `floci.services.eks.default-storage-class: true` (or `FLOCI_SERVICES_EKS_DEFAULT_STORAGE_CLASS=true`) to retain k3s's bundled `local-path` provisioner and default StorageClass.

## Joining EC2 instances as cluster nodes

Floci allows EC2 instances to join an EKS cluster as Kubernetes worker nodes. Launching an instance via `RunInstances` with user data targeting a cluster records its join and, by default, registers a matching Kubernetes node.

### Join mechanisms

Supported user data formats include:
- Amazon Linux 2 `/etc/eks/bootstrap.sh <cluster-name>` commands (plain text, base64, or gzipped base64).
- Amazon Linux 2023 `nodeadm` YAML documents or MIME multipart payloads (`NodeConfig` containing `spec.cluster.name`).

On AWS, EC2 does not fail instance launches if a target cluster is missing. Floci mirrors this: if the target cluster does not exist or node registration fails, Floci logs a warning and allows the launch to proceed.

### Node metadata and AWS outcome alignment

For simulated workers, Floci registers a Kubernetes `Node` matching AWS conventions:
- **Node name**: Uses the instance's private DNS name (`privateDnsName`), matching AWS EKS conventions.
- **Provider ID**: Set to `spec.providerID: aws:///<availability-zone>/<instance-id>`.
- **Labels & Status**: Includes topology and instance-type labels, ready status, internal IP, and catalog capacity.
- **EC2 persistence**: The instance remains an authentic EC2 instance reported by `DescribeInstances`.

### Guest kubelet registration

For custom images that run kubelet, set `kubeletRegistersNode: true` in the
[EC2 image catalog](ec2.md#external-image-catalog). Floci then leaves Node creation,
capacity, allocatable resources and readiness to the guest kubelet, on both initial
join and replay. Join tracking, persisted instance IDs and registration listeners
still run. The image and bootstrap configuration remain responsible for starting
and authenticating kubelet and providing worker networking.

The capability is opt-in per image, including its aliases. A systemd runtime alone
does not enable it; omitted or false values keep the simulated behavior.

### Differences and limitations

- For simulated workers, Floci registers the `Node` directly in the control plane with matching provider ID, labels, and ready status.
- Node cleanup on termination is handled in part two; an instance can belong to at most one cluster at a time.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EKS_ENABLED` | `true` | Enable the EKS service |
| `FLOCI_SERVICES_EKS_MOCK` | `false` | Metadata-only mode (no Docker) |
| `FLOCI_SERVICES_EKS_MAX_MEMORY_MIB` | `0` | Optional memory ceiling in MiB for each node's k3s container |
| `FLOCI_SERVICES_EKS_MAX_VCPUS` | `0` | Optional vCPU ceiling for each node's k3s container |
| `FLOCI_SERVICES_EKS_NODE_INSTANCE_TYPE` | *(unset)* | Catalog instance type node containers are sized from; the API keeps the node group's type |
| `FLOCI_SERVICES_EKS_MAX_NODES_PER_CLUSTER` | `5` | Most nodes run as k3s agents per cluster, across its node groups in creation order; `0` for no cap ([Managed nodes](#managed-nodes)) |
| `FLOCI_SERVICES_EKS_NODE_READY_TIMEOUT_SECONDS` | `900` | How long a node group waits for its nodes to be Ready before it is `CREATE_FAILED` (`NodeCreationFailure`) |
| `FLOCI_SERVICES_EKS_NODE_DRAIN_TIMEOUT_SECONDS` | `900` | How long a version update waits for a node to drain before it fails (`PodEvictionFailure`), unless forced |
| `FLOCI_SERVICES_EKS_LOAD_BALANCER_MODE` | `emulated` | How new clusters give Services of type LoadBalancer their address: `emulated`, `servicelb` or `off` ([LoadBalancer Services](#loadbalancer-services)) |
| `FLOCI_SERVICES_EKS_LOAD_BALANCER_PROVISIONING_DELAY_SECONDS` | `0` | How long a new load balancer takes to get its hostname |
| `FLOCI_SERVICES_EKS_LOAD_BALANCER_HOST_PORTS` | `dynamic` | The `127.0.0.1` port the Docker host reaches each Service port at: `dynamic`, `same-as-service` or `none` |
| `FLOCI_SERVICES_EKS_DEFAULT_IMAGE` | `rancher/k3s:latest` | k3s Docker image fallback |
| `FLOCI_SERVICES_EKS_IMAGE_TEMPLATE` | *(unset)* | Format string for custom k3s images (e.g. `myregistry.io/k3s:v%s`), taking cluster version |
| `FLOCI_SERVICES_EKS_API_SERVER_BASE_PORT` | `6500` | First port in the k3s API server range |
| `FLOCI_SERVICES_EKS_API_SERVER_MAX_PORT` | `6599` | Last port in the k3s API server range |
| `FLOCI_SERVICES_EKS_DATA_PATH` | `./data/eks` | Host bind-mount root for cluster data |
| `FLOCI_SERVICES_EKS_DOCKER_NETWORK` | *(unset)* | Docker network for k3s containers (falls back to the global `FLOCI_SERVICES_DOCKER_NETWORK`, then Floci's own network) |
| `FLOCI_SERVICES_EKS_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave k3s containers running after Floci stops |
| `FLOCI_SERVICES_EKS_ENDPOINT_MODE` | `host` | `describe-cluster` endpoint: `host` (`localhost:<hostPort>`) or `network` (container DNS) |
| `FLOCI_SERVICES_EKS_ENDPOINT_HOST` | _(unset)_ | Hostname or IP address `host` mode advertises instead of `localhost`, for clients on other machines. Added to the k3s certificate as a SAN when the cluster's container is created |
| `FLOCI_SERVICES_EKS_IAM_AUTH_WEBHOOK` | `true` | Wire a token-auth webhook into k3s so `aws eks get-token` works |
| `FLOCI_SERVICES_EKS_ECR_REGISTRY_MIRROR` | `true` | Inject a containerd `registries.yaml` so pods can pull images pushed to [Floci ECR](ecr.md) |
| `FLOCI_SERVICES_EKS_DISABLE_CNI` | `false` | Start k3s without bundled flannel, network policy, and kube-proxy so an external CNI can take over |
| `FLOCI_SERVICES_EKS_DEFAULT_STORAGE_CLASS` | `false` | Retain k3s bundled local-path provisioner and default StorageClass (default false matches EKS with no default class) |
| `FLOCI_SERVICES_EKS_IRSA_SIGNING_KEY` | `true` | Pass the cluster OIDC signing key to k3s so in-cluster projected service account tokens can assume IAM roles via Floci STS |
| `FLOCI_SERVICES_EKS_POD_IDENTITY_WEBHOOK` | `true` | Register a mutating admission webhook that injects pod identity credentials. Needs `FLOCI_TLS_ENABLED=true` |
| `FLOCI_SERVICES_EKS_IMDS` | `false` | Enable link-local IMDS (`169.254.169.254`) proxy in node containers |
| `FLOCI_SERVICES_EKS_EMBEDDED_DNS` | `true` | Inject Floci's embedded DNS server into cluster containers for Route 53 private hosted zones and internal name resolution |
| `FLOCI_SERVICES_EKS_VPC_ROUTE_PROGRAMMING` | `true` | Program emulated VPC route table entries into node containers |

### Kubernetes versions and network configuration

Floci supports standard AWS EKS Kubernetes versions (`1.xx`, 1.28 and above). Known releases are mapped to stable pinned k3s images, while newer or unpinned versions dynamically resolve to upstream k3s release images (`rancher/k3s:v<version>.0-k3s1`):

- `1.28` (`rancher/k3s:v1.28.15-k3s1`)
- `1.29` (`rancher/k3s:v1.29.14-k3s1`, default version metadata)
- `1.30` (`rancher/k3s:v1.30.10-k3s1`)
- `1.31` (`rancher/k3s:v1.31.5-k3s1`)
- `1.32` (`rancher/k3s:v1.32.2-k3s1`)
- `1.33` (`rancher/k3s:v1.33.1-k3s1`)
- `1.34` (`rancher/k3s:v1.34.1-k3s1`)
- `1.35` (`rancher/k3s:v1.35.0-k3s1`)
- `1.36` (`rancher/k3s:v1.36.0-k3s1`)
- `1.37+` (dynamically resolved to `rancher/k3s:v<version>.0-k3s1`)

When `--version` is omitted, the cluster version defaults to `1.29` and the container image resolution uses `FLOCI_SERVICES_EKS_DEFAULT_IMAGE` (`rancher/k3s:latest` by default). When a version is explicitly requested, it maps to the corresponding pinned image or template.

You can specify standard EKS `version` and `kubernetesNetworkConfig.serviceIpv4Cidr` when creating a cluster:

```bash
aws --endpoint-url http://localhost:4566 eks create-cluster \
  --name my-cluster \
  --role-arn arn:aws:iam::000000000000:role/eks-role \
  --version 1.31 \
  --kubernetes-network-config serviceIpv4Cidr=172.20.0.0/16
```

Floci validates that `serviceIpv4Cidr` falls within RFC 1918 private address ranges (`10.0.0.0/8`, `172.16.0.0/12`, or `192.168.0.0/16`), has a prefix length between `/12` and `/24`, and does not overlap with the VPC CIDR. When omitted, Floci defaults `serviceIpv4Cidr` to `10.100.0.0/16` (or `172.20.0.0/16` if `10.100.0.0/16` overlaps with the VPC). Internal pod CIDR (`--cluster-cidr`) defaults to `10.42.0.0/16` to avoid collisions with Docker bridge networks.

### VPC route table programming

When a cluster is created in a VPC (`resourcesVpcConfig.vpcId` is specified) and `floci.services.eks.vpc-route-programming` is enabled (`true` by default), Floci inspects the VPC route tables and programs matching routes into each node's k3s container (the single container of a [cluster created before managed nodes](#single-shared-node-of-earlier-clusters)) via `ip route replace <dest> via <gw>`, and reprograms every node when a route table changes.

- **Association rules**:
  - If the cluster specifies `subnetIds`, Floci resolves the route table explicitly associated with each subnet. Subnets without an explicit association fall back to the VPC main route table. Route tables associated only with other subnets in the VPC are ignored.
  - If the cluster specifies no subnets, the VPC main route table is used.
- **Programmable route targets**:
  - **Instance targets** (`InstanceId`): Resolved to the EC2 container bridge IP or private IP address on the shared Docker network.
  - **Network interface targets** (`NetworkInterfaceId`): Resolved to the ENI private IP address.
- **Ignored and out-of-scope targets**:
  - The default route (`0.0.0.0/0`) is never overridden, preserving the container's external network reachability and Docker bridge routing.
  - Internet gateways (`igw-*`) and NAT gateways (`nat-*`) are out of scope because Docker networking already handles outbound traffic.
  - VPC peering connections (`pcx-*`) and prefix lists are recorded in route table metadata but not programmed into the container kernel routing table.
- **Lifecycle and dynamic updates**:
  - Routes are programmed when a cluster starts or is restored after a restart.
  - An event-driven listener updates active clusters dynamically whenever routes or associations change via `CreateRoute`, `ReplaceRoute`, `DeleteRoute`, `AssociateRouteTable`, or `DisassociateRouteTable`.
- **Fault tolerance**:
  - Route programming is idempotent.
  - If routing commands fail inside the container (for example, if the `ip` tool is missing or returns a non-zero exit code), Floci logs a warning and the cluster proceeds to `ACTIVE` without failing creation.

### Pulling images from Floci ECR

Images pushed to the [Floci ECR registry](ecr.md) use `localhost`-based repository URIs
(for example `000000000000.dkr.ecr.us-east-1.localhost:4566/my-repo:tag`). Inside a k3s
cluster that hostname would resolve to the k3s container itself, and containerd insists
on HTTPS for anything it doesn't recognize as loopback: so, out of the box, k3s cannot
pull from the registry even though `docker push` from the host works.

Floci solves this at cluster creation: each new k3s container gets a generated
`/etc/rancher/k3s/registries.yaml` that mirrors every repository hostname the cluster
can pull: the cluster's own account and the default account, across every region of the
cluster's partition, plus the path-style
`localhost:<port>` form used by `FLOCI_SERVICES_ECR_URI_STYLE=path`, to Floci's
in-network data plane. The same image
reference then works for the host-side push and the in-cluster pull, with no retagging
and no manual containerd configuration:

```bash
aws ecr create-repository --repository-name my-repo
docker build -t 000000000000.dkr.ecr.us-east-1.localhost:4566/my-repo:v1 .
docker push 000000000000.dkr.ecr.us-east-1.localhost:4566/my-repo:v1

aws eks create-cluster --name demo ...
helm install my-app ./chart \
  --set image.repository=000000000000.dkr.ecr.us-east-1.localhost:4566/my-repo \
  --set image.tag=v1
```

Requirements and limits:

- The k3s container must reach Floci's Docker-network address. Set the global
  `FLOCI_SERVICES_DOCKER_NETWORK` as in the standard `docker-compose.yml` or the
  EKS service network variable.
- Only Floci-mintable hostnames are mirrored; public registries (docker.io, ghcr.io, …)
  are never touched.
- Mirrored hostnames carry the port the repository URIs advertise, which is the host port
  when Floci's container publishes `4566` on another one (see [ECR](ecr.md#docker-compose-port-mapping)).
- Repository URIs using a non-default `registryId` (account) are not covered.
- The mirror set is snapshotted when the cluster is created. Clusters created before this
  feature can be fixed manually because the k3s container filesystem survives a restart. A surviving
  container keeps the name it was created with, so those clusters are still `floci-eks-<cluster>`
  while newer ones are `floci-aws-eks-<cluster>`, and a non-default account adds `<account>.` before
  the cluster name. Look the name up first:

  ```bash
  docker ps --filter name=<cluster> --format '{{.Names}}'
  docker cp registries.yaml <container>:/etc/rancher/k3s/registries.yaml
  docker restart <container>
  ```

### DNS resolution and Route 53 private hosted zones

By default, Floci injects its embedded DNS server into each cluster container (`FLOCI_SERVICES_EKS_EMBEDDED_DNS=true`). Cluster containers and CoreDNS forward to Floci's embedded resolver on port 53, enabling resolution of Route 53 private hosted zone records, internal hostnames, and records managed by `external-dns`. Set `FLOCI_SERVICES_EKS_EMBEDDED_DNS=false` to use standard Docker network DNS instead.

### Registry host configuration (pull-through caches and custom mirrors)

On standard EKS (such as AL2023), node registry host configuration is provided through the node
group's launch template user data, which writes containerd
[`hosts.toml`](https://github.com/containerd/containerd/blob/main/docs/hosts.md) configurations
under `/etc/containerd/certs.d/<host>/hosts.toml`.

Floci links `/etc/containerd/certs.d` to k3s's containerd certs directory
(`/var/lib/rancher/k3s/agent/etc/containerd/certs.d`) inside each node's container before it
starts. Standard launch template user data, which runs on each node, works unchanged: pull-through
cache endpoints, custom request headers, and TLS settings take effect immediately, and the files
persist across restarts of the node in its named data volume.

Example node group launch template user data configuring a pull-through cache with an auth header
wrapped in MIME multipart for managed node groups:

```bash
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="==MYBOUNDARY=="

--==MYBOUNDARY==
Content-Type: text/x-shellscript; charset="us-ascii"

#!/bin/sh
mkdir -p /etc/containerd/certs.d/my-registry.internal:5000
cat << 'EOF' > /etc/containerd/certs.d/my-registry.internal:5000/hosts.toml
server = "https://my-registry.internal:5000"

[host."https://cache.internal:5000"]
  capabilities = ["pull", "resolve"]
  [host."https://cache.internal:5000".header]
    Authorization = "Bearer <token>"
EOF

--==MYBOUNDARY==--
```

### Mock mode (CI / tests)

Use `FLOCI_SERVICES_EKS_MOCK=true` when you only need the API shape:

```yaml
# docker-compose.yml: CI / test environment
services:
  floci:
    image: floci/floci:latest
    environment:
      FLOCI_SERVICES_EKS_MOCK: "true"
```

## Instance Metadata Service (IMDS)
Each node is registered as a synthesized EC2 instance regardless of whether IMDS is enabled, making it visible to the EC2 service (`DescribeInstances`, `DescribeInstanceStatus`, volume attachments, tagging), with the node's ID, private IP, AMI and instance type ([Managed nodes](#managed-nodes)). A [cluster created before managed nodes](#single-shared-node-of-earlier-clusters) registers its single container instead (image `ami-eks-k3s`).

When IMDS is enabled (`FLOCI_SERVICES_EKS_IMDS=true`), each node's k3s container exposes the AWS Instance Metadata Service on the link-local address `169.254.169.254:80`. Inside the container, Floci adds `169.254.169.254/32` to the loopback interface (`lo`) and runs a lightweight `socat` TCP relay forwarding metadata requests to Floci's IMDS server.

Both IMDSv1 and IMDSv2 (`PUT /latest/api/token`) are supported. The relay needs `socat` and `curl`, which the stock k3s image lacks, so on stock images it logs a warning and does not run. Each node answers as its synthesized EC2 instance:

```bash
# IMDSv2: obtain token
TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" \
  -H "x-aws-ec2-metadata-token-ttl-seconds: 21600")

# Read node instance ID
curl -s -H "x-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/instance-id
```

### Reachability and network namespaces

The link-local proxy attaches to the loopback interface of the k3s container network namespace (the node network namespace).

- **Reachable from node network namespace:** Workloads configured with `hostNetwork: true` share the node network namespace and can reach `169.254.169.254:80`. This allows local testing of host-network security policies, intrusion-detection rules, and credential exfiltration defenses.
- **Default isolation for ordinary pods:** By default, pods running in separate pod network namespaces cannot reach `169.254.169.254`. CNI portmap rules on the node intercept local traffic, preserving node credential isolation for multi-tenant or untrusted workloads.
- **Opt-in pod network routing:** When `floci.services.eks.imds-pod-network=true` is set alongside `floci.services.eks.imds=true`, iptables DNAT rules are programmed in a custom `FLOCI-LINK-LOCAL` chain at the head of `PREROUTING`. Traffic originating from the pod CIDR (`10.42.0.0/16`) targeting `169.254.169.254:80` matches first and is routed directly to the local node listener, preempting CNI portmap rules. This enables standard AWS SDK credential resolution inside ordinary pods without requiring `hostNetwork: true`.

### Configuration

IMDS proxy initialization is disabled by default (`floci.services.eks.imds=false`). Set `FLOCI_SERVICES_EKS_IMDS=true` to enable the proxy setup inside the k3s container.

Pod network reachability is also disabled by default (`floci.services.eks.imds-pod-network=false`) to maintain pod isolation. Set `FLOCI_SERVICES_EKS_IMDS_POD_NETWORK=true` together with `FLOCI_SERVICES_EKS_IMDS=true` to route pod traffic to the link-local proxy.

A failure to configure the proxy (for example on custom minimal images lacking network utilities) logs a warning and allows cluster startup to continue.

### Hop limits

EC2 metadata options store `HttpPutResponseHopLimit`, but the userspace TCP proxy relay terminates the incoming connection and opens a new connection to Floci, regenerating the IP packet TTL. Hop limits are recorded on the instance metadata options model but are not enforced by the userspace proxy.

## IRSA (IAM Roles for Service Accounts)

Every cluster gets an OIDC identity provider, so the full IRSA flow (trust policy, service-account token, `sts:AssumeRoleWithWebIdentity`) works end to end without mocked or hardcoded tokens.

`CreateCluster` generates an RSA-2048 signing keypair and an AWS-shaped issuer URL, returned by `DescribeCluster`:

```json
{
  "cluster": {
    "name": "my-cluster",
    "identity": { "oidc": { "issuer": "https://oidc.eks.us-east-1.amazonaws.com/id/3F8A…" } }
  }
}
```

The issuer URL is a faithful string for building trust policies, but it is not fetched: Floci's STS resolves the signing key in-process. The private key is held in storage separate from the cluster model and is never returned by any API.

### Service-account tokens

Floci supports two ways to obtain IRSA service account tokens:

#### 1. In-cluster projected tokens (real k3s mode)

In real mode (`mock: false`), Floci starts a k3s container for the cluster. When IRSA signing key support is enabled (`FLOCI_SERVICES_EKS_IRSA_SIGNING_KEY=true`, default `true`), Floci exports the cluster RSA keypair to `/etc/rancher/k3s/sa-signing-key.pem` and `/etc/rancher/k3s/sa-public-key.pem` with restrictive filesystem permissions (`0600`) before k3s starts.

The k3s API server is configured with:
- `--kube-apiserver-arg=service-account-signing-key-file=/etc/rancher/k3s/sa-signing-key.pem`
- `--kube-apiserver-arg=service-account-key-file=/etc/rancher/k3s/sa-public-key.pem`
- `--kube-apiserver-arg=service-account-issuer=<cluster-oidc-issuer>`
- `--kube-apiserver-arg=service-account-issuer=https://kubernetes.default.svc.cluster.local`
- `--kube-apiserver-arg=api-audiences=https://kubernetes.default.svc.cluster.local,sts.amazonaws.com`

This enables in-cluster pods to project service account tokens directly using Kubernetes standard projected volumes:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: irsa-workload
  namespace: default
spec:
  serviceAccountName: my-service-account
  containers:
  - name: workload
    image: my-workload:latest
    env:
    - name: AWS_ROLE_ARN
      value: arn:aws:iam::000000000000:role/my-irsa-role
    - name: AWS_WEB_IDENTITY_TOKEN_FILE
      value: /var/run/secrets/eks.amazonaws.com/serviceaccount/token
    volumeMounts:
    - mountPath: /var/run/secrets/eks.amazonaws.com/serviceaccount
      name: aws-iam-token
      readOnly: true
  volumes:
  - name: aws-iam-token
    projected:
      sources:
      - serviceAccountToken:
          path: token
          expirationSeconds: 86400
          audience: sts.amazonaws.com
```

Tokens projected with audience `sts.amazonaws.com` are signed by k3s using the cluster key and advertise the cluster issuer URL. When the AWS SDK inside the container calls `sts:AssumeRoleWithWebIdentity`, Floci verifies the signature against the cluster public key and checks the trust policy conditions. In-cluster components that do not specify an audience receive tokens with both the in-cluster audience and STS audience, allowing normal Kubernetes operation without disruption.

#### 2. Minting a service-account token via HTTP (mock mode & out-of-cluster testing)

For local development harnesses running outside Kubernetes or when using mock mode (`FLOCI_SERVICES_EKS_MOCK=true`), tokens can be requested directly from Floci via HTTP. Signing happens server-side, so the private key never leaves Floci:

```bash
curl -sX POST http://localhost:4566/_floci/eks/clusters/my-cluster/oidc-token \
  -H 'Content-Type: application/json' \
  -d '{"namespace":"my-namespace","serviceAccount":"my-service-account"}'
```

```json
{
  "token": "eyJhbGciOiJSUzI1NiIs…",
  "issuer": "https://oidc.eks.us-east-1.amazonaws.com/id/3F8A…",
  "subject": "system:serviceaccount:my-namespace:my-service-account",
  "audience": "sts.amazonaws.com"
}
```

`audience` (default `sts.amazonaws.com`) and `expirySeconds` (default 24h, max 7d) are optional. This is Floci plumbing under `_floci/…`, not an AWS API.

### Trust policy

Note that the OIDC provider ARN and the condition keys use the issuer with the scheme stripped `oidc.eks.<region>.amazonaws.com/id/<id>`, which is how the AWS console, `eksctl`, and Terraform all render it.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "arn:aws:iam::000000000000:oidc-provider/<oidcProvider>"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "<oidcProvider>:sub": "system:serviceaccount:<namespace>:<serviceAccount>",
        "<oidcProvider>:aud": "sts.amazonaws.com"
      }
    }
  }]
}
```

### What STS validates

When `sts:AssumeRoleWithWebIdentity` receives a token whose `iss` names an issuer Floci hosts, it enforces all of:

- the RS256 signature, against that cluster's public key
- `iss` matches the cluster's issuer exactly
- `aud` contains `sts.amazonaws.com`
- `exp` / `nbf`, with 60s of clock-skew tolerance
- the role's trust policy: `Principal.Federated` and the `Condition` block, comparing `oidc:sub` / `oidc:aud` with exact, case-sensitive equality

The response then carries the token's real claims in `SubjectFromWebIdentityToken`, `Provider`, and `Audience`. Failures return `InvalidIdentityToken` (400) for a bad token, `ExpiredTokenException` (400) for an expired one, or `AccessDenied` (403) when the trust policy does not permit the subject.

A token whose issuer Floci does not host is treated as opaque and accepted, since Floci cannot adjudicate a third-party provider. Validation is therefore automatic for Floci-issued tokens and requires no configuration flag.

### OIDC discovery endpoints

Served for fidelity and debugging, nothing in the IRSA flow dereferences them:

| Route | Description |
|---|---|
| `GET /_floci/eks/clusters/<name>/oidc/.well-known/openid-configuration` | OIDC discovery document |
| `GET /_floci/eks/clusters/<name>/oidc/keys` | JWKS containing the cluster's public key |

These live under `_floci/…` rather than at the AWS-shaped issuer path, which Floci's embedded DNS does not resolve and which would collide with S3's path-style routing.

## ARN Format

```
arn:aws:eks:<region>:<accountId>:cluster/<clusterName>
```

Node groups use:

```
arn:aws:eks:<region>:<accountId>:nodegroup/<clusterName>/<nodegroupName>/<id>
```

Fargate profiles use:

```
arn:aws:eks:<region>:<accountId>:fargateprofile/<clusterName>/<fargateProfileName>/<id>
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a cluster
aws eks create-cluster \
  --name my-cluster \
  --role-arn arn:aws:iam::000000000000:role/eks-role \
  --resources-vpc-config subnetIds=[],securityGroupIds=[] \
  --kubernetes-version 1.29

# Describe the cluster
aws eks describe-cluster --name my-cluster

# List clusters
aws eks list-clusters

# Create a node group
curl -s -X POST "$AWS_ENDPOINT_URL/clusters/my-cluster/node-groups" \
  -H "Content-Type: application/json" \
  -d '{
    "nodegroupName": "my-nodegroup",
    "nodeRole": "arn:aws:iam::000000000000:role/eks-node-role",
    "subnets": ["subnet-123", "subnet-456"],
    "instanceTypes": ["t3.medium"],
    "scalingConfig": {
      "minSize": 1,
      "maxSize": 3,
      "desiredSize": 1
    }
  }'

# Describe the node group
curl -s "$AWS_ENDPOINT_URL/clusters/my-cluster/node-groups/my-nodegroup"

# List node groups
curl -s "$AWS_ENDPOINT_URL/clusters/my-cluster/node-groups"

# Delete the node group
curl -s -X DELETE "$AWS_ENDPOINT_URL/clusters/my-cluster/node-groups/my-nodegroup"

# Create a Fargate profile
curl -s -X POST "$AWS_ENDPOINT_URL/clusters/my-cluster/fargate-profiles" \
  -H "Content-Type: application/json" \
  -d '{
    "fargateProfileName": "my-fargate-profile",
    "podExecutionRoleArn": "arn:aws:iam::000000000000:role/eks-fargate-role",
    "subnets": ["subnet-123", "subnet-456"],
    "selectors": [
      {
        "namespace": "default",
        "labels": {
          "app": "api"
        }
      }
    ],
    "tags": {
      "env": "dev"
    }
  }'

# Describe the Fargate profile
curl -s "$AWS_ENDPOINT_URL/clusters/my-cluster/fargate-profiles/my-fargate-profile"

# List Fargate profiles
curl -s "$AWS_ENDPOINT_URL/clusters/my-cluster/fargate-profiles"

# Delete the Fargate profile
curl -s -X DELETE "$AWS_ENDPOINT_URL/clusters/my-cluster/fargate-profiles/my-fargate-profile"

# Tag a cluster
aws eks tag-resource \
  --resource-arn arn:aws:eks:us-east-1:000000000000:cluster/my-cluster \
  --tags env=dev,team=platform

# Delete a cluster
aws eks delete-cluster --name my-cluster
```

## Java SDK Example

```java
EksClient eks = EksClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

// Create cluster
CreateClusterResponse created = eks.createCluster(r -> r
    .name("my-cluster")
    .roleArn("arn:aws:iam::000000000000:role/eks-role")
    .resourcesVpcConfig(v -> v
        .subnetIds(List.of())
        .securityGroupIds(List.of()))
    .version("1.29")
    .tags(Map.of("env", "dev")));

// Describe cluster
DescribeClusterResponse described = eks.describeCluster(r -> r
    .name("my-cluster"));

System.out.println(described.cluster().status()); // ACTIVE

// List clusters
List<String> names = eks.listClusters(r -> {}).clusters();

// Node group and Fargate profile support are currently exposed through the REST paths.

// Tag resource
eks.tagResource(r -> r
    .resourceArn(created.cluster().arn())
    .tags(Map.of("team", "platform")));

// Delete cluster
eks.deleteCluster(r -> r.name("my-cluster"));
```

## Not Implemented

The following EKS features are not yet supported:

- `UpdateClusterConfig` (endpoint access, logging, authentication mode and the other cluster configuration updates)
- `CancelUpdate`, and `UpdateClusterVersion` rollbacks (`rollbackConfig`)
- `UpdateAccessEntry` and `ListAccessPolicies`
- Identity provider configs
