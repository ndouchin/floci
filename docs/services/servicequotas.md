# Service Quotas

**Protocol:** JSON 1.1 (`X-Amz-Target: ServiceQuotasV20190624.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `servicequotas`

Floci answers Service Quotas lookups from a generated in-memory catalog. Every service code
resolves to a quota list: a curated set with real AWS quota codes where tooling depends on
them — CodeBuild's `L-2DC20C30` ("Concurrently running builds") and Lambda's `L-B99A9384`
("Concurrent executions") — plus a deterministic generic set for any other service code.
Unmodeled values are deliberately generous so local pipelines that gate on quota headroom, such
as AWS Landing Zone Accelerator, never stall on a limit the emulator does not enforce. The
Organizations `Maximum number of accounts` quota uses AWS quota code `L-E619E033` and a local value
of 50. VPC's `L-FE5A380F` ("NAT gateways per Availability Zone", 5) and `L-2AEEBF1A` ("Rules per
network ACL", 20) carry their AWS default values, so tooling that compares a desired value against
the default before requesting an increase takes the same path it would against AWS.
EC2's `L-1216C47A` ("Running On-Demand Standard (A, C, D, H, I, M, R, T, Z)
instances") reports a configurable applied value through `GetServiceQuota` and
`ListServiceQuotas`. Set `FLOCI_SERVICES_SERVICEQUOTAS_STANDARD_ON_DEMAND_VCPUS` to
change that value for all local accounts and regions. Use a nonnegative integer;
negative values prevent Floci from starting, even when Service Quotas is disabled.
It defaults to 5. The AWS
default read actions always return 5. EC2 can use this applied value for opt-in
Standard On-Demand launch/start admission for each account and region; see [EC2 admission](ec2.md#standard-on-demand-admission).

### Quotas the ROSA CLI checks

`rosa create account-roles` and `rosa verify quota` list the quotas of five service codes and
compare twelve of them with the values a cluster install needs (rosa v1.2.68,
[`pkg/aws/quota.go`](https://github.com/openshift/rosa/blob/v1.2.68/pkg/aws/quota.go) and IAM's
managed policies per role in
[`pkg/policy/policy_service.go`](https://github.com/openshift/rosa/blob/v1.2.68/pkg/policy/policy_service.go)).
Floci carries each of them with its AWS code, name and default value:

| Service code | Quota code | Name | AWS default | rosa wants |
| --- | --- | --- | --- | --- |
| `ec2` | `L-0263D0A3` | EC2-VPC Elastic IPs | 5 | 5 |
| `ec2` | `L-1216C47A` | Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances | 5 (see above) | 100 |
| `vpc` | `L-F678F1CE` | VPCs per Region | 5 | 5 |
| `vpc` | `L-A4707A72` | Internet gateways per Region | 5 | 5 |
| `vpc` | `L-DF5E4CA3` | Network interfaces per Region | 5000 | 5000 |
| `ebs` | `L-D18FCD1D` | Storage for General Purpose SSD (gp2) volumes, in TiB | 50, 300 outside the sixteen original regions | 50 |
| `ebs` | `L-309BACF6` | Snapshots per Region | 100000 | 300 |
| `ebs` | `L-B3A130E6` | IOPS for Provisioned IOPS SSD (io1) volumes | 300000 | 300000 |
| `ebs` | `L-FD252861` | Storage for Provisioned IOPS SSD (io1) volumes, in TiB | 50, 300 outside the sixteen original regions | 50 |
| `elasticloadbalancing` | `L-53DA6B97` | Application Load Balancers per Region | 50 | 50 |
| `elasticloadbalancing` | `L-E9E9831D` | Classic Load Balancers per Region | 20 | 20 |
| `iam` | `L-0DA4ABF3` | Managed policies per role | 10 | one more than the role has |

Defaults are from AWS's quota pages for [VPC](https://docs.aws.amazon.com/vpc/latest/userguide/amazon-vpc-limits.html),
[EBS](https://docs.aws.amazon.com/general/latest/gr/ebs-service.html),
[Application](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-limits.html) and
[Classic](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-limits.html) Load Balancers and
[IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_iam-quotas.html). With them, rosa's check fails
where it fails on a new AWS account, on the vCPU quota (`Service ec2 quota code L-1216C47A ... expected quota of at
least 100, but got 5`). Raise it with `FLOCI_SERVICES_SERVICEQUOTAS_STANDARD_ON_DEMAND_VCPUS=100`, or with
`FLOCI_SERVICES_SERVICEQUOTAS_APPLIED_VALUES=ec2/L-1216C47A=100`.

`FLOCI_SERVICES_SERVICEQUOTAS_APPLIED_VALUES` sets the applied value of any quota in the catalog, as a
comma-separated list of `<serviceCode>/<quotaCode>=<value>` entries; an `ec2/L-1216C47A` entry wins over the vCPU setting, in EC2 admission too. The AWS
default read actions keep returning AWS's defaults. A malformed entry, or a negative value, stops Floci at startup.

All other applied quotas and AWS default quotas return the same catalog data.
`RequestServiceQuotaIncrease` is accepted and validated, records the request, and lets you read
it back with `GetRequestedServiceQuotaChange` or list it with
`ListRequestedServiceQuotaChangeHistoryByQuota`, but it does not change any quota value. See
Limitations.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListServiceQuotas` | Lists the quotas for a service code; generated quotas for unknown codes |
| `GetServiceQuota` | Returns one quota by service and quota code, else `NoSuchResourceException` |
| `GetAWSDefaultServiceQuota` | Returns the catalog default, independent of the configured applied values |
| `ListAWSDefaultServiceQuotas` | Lists catalog defaults, independent of the configured applied values |
| `RequestServiceQuotaIncrease` | Validates and stores an increase request as `PENDING`; a second open request for the same quota is refused with `ResourceAlreadyExistsException` |
| `GetRequestedServiceQuotaChange` | Returns a stored request by id, else `NoSuchResourceException` |
| `ListRequestedServiceQuotaChangeHistoryByQuota` | Lists the stored requests for a service/quota pair, newest first |
| `ListServices` | Lists the available services with their codes and names, supports pagination via MaxResults and NextToken |
<!-- floci:actions:end -->

## Limitations

- **Quota increase requests are persisted per region, but `Status` never advances.** A
  successful `RequestServiceQuotaIncrease` is stored under its own id and can be read back with
  `GetRequestedServiceQuotaChange` or listed with `ListRequestedServiceQuotaChangeHistoryByQuota`
  in the region that created it. Because a request stays `PENDING`, a second
  `RequestServiceQuotaIncrease` for the same quota in the same region is refused with
  `ResourceAlreadyExistsException`, matching AWS's one-open-request-per-quota rule. Nothing
  processes a request, however, so it stays `PENDING` and
  never reaches `APPROVED`, `CASE_OPENED`, or `DENIED`. `PENDING` is what real AWS returns on
  creation, so a caller that reads the creation response sees faithful data; a caller that polls
  for completion will wait forever.
- **Unknown service codes generate a quota catalog rather than failing.** This is deliberate, so
  the code has to be well-formed to be trusted: `ServiceCode` is validated against the modeled
  `[a-zA-Z][a-zA-Z0-9-]{1,63}` (max 63 characters) and a malformed one is rejected with
  `IllegalArgumentException` instead of returning invented quotas.
- **A requested increase does not change the quota.** `GetServiceQuota` continues to return
  configured applied value or catalog value after a successful increase request. Increase
  requests do not update local configuration or enforce capacity limits.
- `ListRequestedServiceQuotaChangeHistory` (the account-wide variant without a quota filter) is
  not implemented.
- `CaseId` is never returned; no support case is opened. `SupportCaseAllowed` in the request is
  accepted and ignored, as the emulator has no case-opening path either way.
- The quota-increase template operations (`PutServiceQuotaIncreaseRequestIntoTemplate` and
  related) are not implemented.

## Configuration

| Environment variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_SERVICEQUOTAS_ENABLED` | `true` | Enables the service |
| `FLOCI_SERVICES_SERVICEQUOTAS_STANDARD_ON_DEMAND_VCPUS` | `5` | Applied EC2 Standard On-Demand vCPU quota reported per local account and region; used by opt-in EC2 admission |
| `FLOCI_SERVICES_SERVICEQUOTAS_APPLIED_VALUES` | none | Applied values that differ from AWS's defaults, as `<serviceCode>/<quotaCode>=<value>` entries separated by commas, for example `ec2/L-1216C47A=100,vpc/L-F678F1CE=10` |

## Example

```bash
aws --endpoint-url http://localhost:4566 service-quotas list-service-quotas \
  --service-code codebuild

aws --endpoint-url http://localhost:4566 service-quotas get-service-quota \
  --service-code codebuild --quota-code L-2DC20C30
```
