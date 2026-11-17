# FraudShield

FraudShield is the real-time payment fraud detection platform described in `FraudShield_Design_Document.pdf` (Draft 1.0, 1 November 2026). This repository holds its first deployable release: Spring Boot services, the Angular investigator portal, Azure infrastructure in **UK South and UK West**, Kubernetes deployment, and CI/CD.

> **Status:** the services and portal are built and tested locally: 28 Java tests and 8 portal tests pass, and an end-to-end run of all four services works. Terraform validates offline. Nothing has been deployed to Azure yet. Expect a round of fixes on the first `infra.yml` run (Jira FS-45). **Do not use with live payments** until the items in [Before live payments](#before-live-payments) are closed.

## What is included

| Area | Path | Design section |
|---|---|---|
| Spring Boot services: decision, case, strategy, copilot | `services` | 4, 5, 7 |
| Angular investigator portal: the six screens from the design | `portal` | 9, 10 |
| Terraform: hub-and-spoke network, AKS, API Management, Front Door + WAF, data stores, lakehouse, Foundry, monitoring, Key Vault | `infra/terraform` | 3, 5, 6, 11, 12 |
| One-time state storage and shared registry | `infra/bootstrap.sh` | 13 |
| Helm chart for every service, with Argo Rollouts canary (5%, 25%, 100%) and automatic rollback | `deploy/helm/fraudshield-service` | 8, 13 |
| Argo CD ApplicationSet and per-environment values | `deploy/gitops` | 13 |
| Cluster add-ons: internal ingress, Argo CD, Argo Rollouts, Prometheus | `deploy/platform` | 13 |
| Databricks Asset Bundle skeleton | `databricks` | 8 |
| Azure DevOps pipelines, and GitHub Actions for infrastructure and model releases | `azure-pipelines`, `.github` | 8, 13, 14 |
| Jira backlog: 16 epics, 171 issues, sprints 1 to 6 | `delivery` | 15 |

### Not included

- Unity Catalog metastore, catalogs, and external locations (account-level Databricks setup), and the bundle's jobs.
- API Management APIs, policies, and channel mTLS certificates.
- Customer-managed keys in Key Vault Managed HSM, Purview, ServiceNow integration, and the ExpressRoute circuit connection.

Each of these has a Jira ticket.

## Environments

| Environment | Regions | Address space | Data |
|---|---|---|---|
| dev | UK South | 10.60.0.0/16 | Synthetic only |
| test | UK South | 10.61.0.0/16 | Tokenised samples |
| perf | UK South + UK West | 10.62.0.0/16 | Synthetic at production scale |
| prod | UK South + UK West, active-active | 10.63.0.0/16 | Live |

Each region gets a /17: hub /22 at `+0.0`, application spoke /19 at `+32.0`, and, in the primary region only, data spoke /19 at `+64.0`. UK West has no availability zones, so zonal settings apply in UK South only. Front Door's WAF allows UK traffic only, Communication Services keeps data in the UK, and maintenance windows run at 02:00 UK time.

**Perf and prod are expensive** (APIM Premium in two regions, Firewall Premium, Business Critical SQL, Event Hubs Premium). Scale perf down between test windows.

## Application

### Services (Java 21, Spring Boot 4)

One Maven build in `services`, with a shared `common` module for Entra sign-in, roles, errors, and service-to-service calls.

| Service | APIs | What it does |
|---|---|---|
| decision | `POST /api/v1/decisions`, `GET /api/v1/decisions/status` | The section 4 flow: validate and check `Idempotency-Key`, absolute rules (sanctions and blocklists), features with a 25 ms timeout and safe defaults, score, then policy (the stricter of rules and thresholds). If scoring fails, fallback rules with stricter channel limits apply. Rules and thresholds reload live from strategy, and the pod is not ready until they have loaded. Alerts and counts are sent on asynchronously. |
| case | `/api/v1/alerts`, `/api/v1/cases`, `/api/v1/overview` | Alert intake (idempotent), queue ordered by score then SLA, assign next, case view with evidence and linked entities, actions with a required reason, append-only history, outcomes for training labels (corrections add a row), and the operations overview. |
| strategy | `/api/v1/rules`, `/api/v1/thresholds`, `/api/v1/models` | Rule authoring and versions, threshold simulator over 30 days of scored history, two-person approval (the author can never approve, even with both roles), the live rule snapshot for decision, model monitoring, and release approval (not the model's author or requester). |
| copilot | `POST /api/v1/copilot/cases/{id}/summary` | Grounded case summary. It reads the case with the analyst's own token, cites a source for every statement, and drops statements with unsupported numbers or decision language. Template-based by default; Azure OpenAI after approval. No tools that can change a case. |

The design's worked example is a test: £9,850 to a new payee, with a remote-access tool, from a customer who usually pays £210. The decision service holds it with score 98.

**Entra app roles** on `fraudshield-api`: `ANALYST`, `TEAM_LEAD`, `STRATEGIST`, `RISK_APPROVER`, `ML_ENGINEER`, `MODEL_RISK_OWNER`, `CHANNEL` (payment channels, through API Management), and `DECISION_ENGINE` (the decision service's identity).

### Portal (Angular 21)

The six screens from design section 9, with the mock-ups' layout and colours: **Overview**, **Alert queue**, **Case investigation** (actions, AI draft, linked-entity graph), **Rules and thresholds** (simulator and approvals), **Model monitoring**, and **Release approval**, plus Cases and Reports. It uses standalone components, signals, and lazy-loaded screens; Apache ECharts for charts and Cytoscape.js for the entity graph; MSAL for Entra sign-in; account tokens masked; keyboard access and screen-reader text for charts.

### Run locally

Needs Java 21 and Node 24. Local mode uses in-memory databases, synthetic data that mirrors the mock-ups, and a person picker instead of sign-in.

```bash
cd services && ./mvnw -q -DskipTests package
java -jar case/target/app.jar --spring.profiles.active=local       # :8082
java -jar strategy/target/app.jar --spring.profiles.active=local   # :8083
java -jar copilot/target/app.jar --spring.profiles.active=local    # :8084
java -jar decision/target/app.jar --spring.profiles.active=local   # :8080

cd portal && npm ci && npx ng serve                                # http://localhost:4200
```

Switch person in the top right: J. Analyst (queue and cases), S. Strategist (rules), R. Approver (approves rules and thresholds), M. Engineer (trained fs-txn v15, so cannot approve it), K. Risk Owner (approves releases).

### Test

```bash
cd services && ./mvnw verify
cd portal && CI=1 npx ng test --watch=false
```

### Before live payments

The first release is complete enough to run end to end, but these parts are deliberately simple. Each has a Jira ticket.

- **Features and idempotency are in memory per pod.** Velocity counters and profiles move to Managed Redis and Cosmos DB (FS-111). Idempotency keys and the decision event queue move to the Cosmos DB outbox and Event Hubs (FS-112).
- **No trained model yet.** Decisions use `fs-baseline-1`, a transparent hand-weighted score labelled as such everywhere. Releasing a model before the ONNX scorer exists (FS-113) makes the decision service refuse to start, rather than score silently with something else.
- **Rule backtests** return "not set up" until Databricks SQL is connected (FS-115). The threshold simulator works from the `score_distribution` table.
- **Live updates** poll every 30 seconds; SignalR push is FS-114.
- **Release approvals** are recorded in strategy, and must also be written to the Unity Catalog tag the pipeline checks (FS-116).
- **UK regulation.** The design was written for Australian regulation. Compliance must confirm the UK obligations (PCI DSS, PRA and FCA operational resilience, UK GDPR, PSR APP-scam reimbursement, Money Laundering Regulations) and re-baseline design section 12.
## How it fits together

```
Payment channels ──(ExpressRoute, mTLS)──► API Management (internal) ─┐
Portal users ──► Front Door + WAF ──► Application Gateway ─┬─ /api/* ──┘──► AKS internal ingress ──► services
                                                            └─ /* ───────────► AKS internal ingress ──► portal
```

- **Two paths.** The decision path (AKS, Redis, Cosmos DB, SQL, Event Hubs) does not depend on Databricks.
- **Private only.** Every data service has public access disabled and is reached through private endpoints. Each region has its own private DNS zones, so a service always resolves to an endpoint in its own region.
- **Egress control.** AKS traffic leaves only through Azure Firewall, which allows the shared registry, Microsoft endpoints, and Azure DevOps. Third-party images are imported into the registry by `platform.yml`.
- **No secrets.** Services use workload identity, with one managed identity per service. Pipelines use workload identity federation.

### Configuration and release state

Argo CD reads two branches of this repository:

| Branch | Contents | Changed by |
|---|---|---|
| `main` | Chart and values: `deploy/helm`, `deploy/gitops` | Pull requests |
| `releases` | `environments/<env>/release.yaml` (image tag), `model.yaml` (model versions), `platform-<region>.yaml` (Terraform outputs) | Pipelines only |

Promoting a release is therefore a commit on `releases`. The pipelines then wait until every Rollout in every cluster is healthy. A breached canary limit rolls back in the cluster, and the pipeline stage fails.

## Pipelines

| Pipeline | Runs when | What it does |
|---|---|---|
| `ci.yml` | Pull requests | Terraform format, validate, and Trivy scan; renders the chart for every service in every environment and validates it with kubeconform; shellcheck; service tests |
| `infra.yml` | Merges to `infra/terraform`, or by hand | Plans, then applies the saved plan: dev, then test. Perf and prod only when chosen. Publishes platform values to `releases` |
| `platform.yml` | Changes to `deploy/platform` or `deploy/gitops/argocd`, or by hand | Installs cluster add-ons and the ApplicationSet in each cluster through `az aks command invoke` |
| `app.yml` | Merges to `services`, `portal`, or `deploy/helm` | Builds every image once, scans it with Trivy, then releases dev, test, and, when chosen, perf and prod |
| `databricks.yml` | Merges to `databricks` | Validates and deploys the bundle per environment |
| `model-release.yml` | By hand | Checks the section 8 promotion gates, then either shadows a model version or releases it as a canary. Afterwards it moves the `champion` alias |

### Model promotion gates

`azure-pipelines/scripts/check_model_gates.py` reads Unity Catalog model version tags. The Databricks validation jobs must write them.

| Gate | Tags | Pass when |
|---|---|---|
| Validation | `validation.recall_at_alert_rate`, `validation.champion_recall_at_alert_rate`, `validation.pr_auc`, `validation.champion_pr_auc` | Recall beats the champion, and PR-AUC is no worse |
| Fairness | `fairness.status` | `passed` |
| Explainability | `explainability.reason_codes_reviewed`, `model_card.url` | `true`, and a model card exists |
| Performance | `performance.inference_p99_ms`, `performance.memory_within_limit` | Under 15 ms, and `true` |
| Signed artifact | `onnx.sha256`, `onnx.signature_key_id` | Both present |
| Shadow (release only) | `shadow.stable_days`, `shadow.feature_issues` | 14 days or more, and `0` |
| Approval (release only) | `approval.approved_by` | Set, and not the version's author |

### Metrics the services must publish

Canary analysis queries in-cluster Prometheus:

- `http_server_requests_seconds_*` (Spring Boot defaults) for error rate and p99 latency
- `fraudshield_decisions_total{outcome_group="allow|step_up|alert"}` from the decision service, for alert-rate and step-up-rate drift
- `fraudshield.decision.fallback` (custom metric in Application Insights), which raises the rules-only fallback alert

## First-time setup

### 1. Shared Azure resources

As an Azure administrator:

```bash
./infra/bootstrap.sh <prefix>      # 3 to 8 lowercase letters or digits
```

### 2. Entra ID

- App registration **fraudshield-api**: Application ID URI `api://<client ID>`, scope `access_as_user`, `accessTokenAcceptedVersion: 2`, and the eight app roles above. After the first apply, assign `DECISION_ENGINE` to the decision service's managed identity (`id-fraudshield-<env>-decision`).
- App registration **fraudshield-portal**: single-page application, granted `access_as_user`. Add the portal URL as a redirect URI.
- A group to administer Azure SQL. SQL authentication is disabled. After the first apply, it runs `CREATE USER [id-fraudshield-<env>-case] FROM EXTERNAL PROVIDER` (and the same for strategy) with read, write, and DDL rights for Flyway.
- Optional: groups for AKS cluster admin. Use PIM-activated groups in production.

### 3. Azure DevOps

1. Push this folder as the root of a repository.
2. Create the service connection `fraudshield-azure` with workload identity federation. Give its identity:
   - **Owner** on each environment subscription, or **Contributor** plus **Role Based Access Control Administrator** (Terraform creates role assignments)
   - **Storage Blob Data Contributor** on the state account
   - **AcrPush** and **Contributor** on the shared registry (`az acr import`)
3. Give **Project Build Service** *Contribute* and *Create branch* on the repository, so pipelines can write to `releases`. Deny *Contribute* on `releases` to everyone else.
4. Create variable groups.

   **`fraudshield-shared`** holds the values printed by `bootstrap.sh`, plus `GITOPS_REPO_URL` (the repository's HTTPS clone URL).

   **`fraudshield-dev`, `-test`, `-perf`, `-prod`:**

   | Variable | Example | Notes |
   |---|---|---|
   | `APIM_PUBLISHER_EMAIL` | `fraud-platform@bank.example` | |
   | `SQL_ADMIN_GROUP_NAME` | `sg-fraudshield-sql-admins` | |
   | `SQL_ADMIN_GROUP_OBJECT_ID` | `0000…` | |
   | `CHANNEL_SOURCE_CIDRS` | `["10.10.0.0/16"]` | Payment switch ranges. Use `[]` in dev |
   | `AKS_ADMIN_GROUP_OBJECT_IDS` | `[]` | |
   | `ALERT_EMAILS` | `["oncall@bank.example"]` | |
   | `ENTRA_API_CLIENT_ID` | `0000…` | fraudshield-api client ID |
   | `ENTRA_SPA_CLIENT_ID` | `0000…` | fraudshield-portal client ID |
   | `ORIGIN_HOST_NAME` | `portal-origin.fraudshield.bank.example` | Leave empty, with the next one, to skip the edge |
   | `ORIGIN_CERTIFICATE_SECRET_ID` | `https://kv-….vault.azure.net/secrets/origin` | Versionless |
   | `AKS_CLUSTERS` | `rg-fraudshield-dev-uks/aks-fraudshield-dev-uks` | `infra.yml` prints this after the first apply |
   | `GITOPS_REPO_TOKEN` | *(secret)* | Read-only token that Argo CD uses for this repository |
   | `DATABRICKS_HOST` | `https://adb-….azuredatabricks.net` | `databricks_workspace_url` output |

5. Create environments `fraudshield-dev`, `-test`, `-perf`, and `-prod`. Add approvers to perf and prod. Prod approvers must not be the change author.
6. Create one pipeline for each YAML file in `azure-pipelines`, and make `ci.yml` a required check on `main`.
7. For prod: create the agent pool `fraudshield-private` inside the network (Managed DevOps Pool). The prod Databricks workspace is private, and `databricks.yml` and `model-release.yml` need it.

### 4. First deployment of an environment

1. Run **infra** (the first apply takes about 1 to 2 hours, mostly API Management). Set `AKS_CLUSTERS` from its output.
2. Run **platform** for that environment.
3. Grant the case and strategy identities access to Azure SQL, and assign `DECISION_ENGINE` to the decision identity (see step 2).
4. Run **app**. It builds decision, case, strategy, copilot, and portal, and releases them to dev and test.
5. When the origin certificate is in Key Vault, set the two `ORIGIN_*` variables and run **infra** again to create Front Door.

## GitHub Actions (infrastructure and model releases)

`.github/workflows` has GitHub Actions versions of `infra.yml` and `model-release.yml`. They behave the same way and use the same scripts in `azure-pipelines/scripts`, which detect which system they run in. **Run each pipeline from one system only.** Both use the same Terraform state and the same `releases` branch.

| Workflow | Runs when | What it does |
|---|---|---|
| `infra.yml` (Infrastructure) | Merges that change `infra/terraform`, or by hand | Plan and apply for dev, then test. Perf and prod only when chosen. Prod runs after perf if both are chosen |
| `model-release.yml` (Model release) | By hand | Gates, then shadow or canary release through dev, test, perf, and prod, then the `champion` alias |
| `_terraform.yml`, `_model-promote.yml` | Called by the two above | One environment each |

GitHub reads workflows only from the repository root, so `FraudShield` must be the root of the repository.

### Set up GitHub

1. Create a Microsoft Entra application for GitHub, with the same Azure roles as the Azure DevOps service connection. Add a federated credential for each GitHub environment below, with the subject `repo:<owner>/<repo>:environment:<environment name>`.
2. Create these environments:

   | Environment | Used by | Set up |
   |---|---|---|
   | `fraudshield-dev-plan`, `-test-plan`, `-perf-plan`, `-prod-plan` | Terraform plan | The Terraform input variables in the next table. No reviewers |
   | `fraudshield-dev`, `-test`, `-perf`, `-prod` | Terraform apply and model promotion | Restrict to `main`. Add required reviewers to perf and prod; prod reviewers must not be the change author. Variable `AKS_CLUSTERS` |
   | `fraudshield-models` | Gate check and champion alias | Restrict to `main`. Variable `DATABRICKS_HOST` (production workspace). The identity needs access to the Unity Catalog models |

   Terraform input variables for each `-plan` environment: `APIM_PUBLISHER_EMAIL`, `SQL_ADMIN_GROUP_NAME`, `SQL_ADMIN_GROUP_OBJECT_ID`, `CHANNEL_SOURCE_CIDRS`, `AKS_ADMIN_GROUP_OBJECT_IDS`, `ALERT_EMAILS`, `ORIGIN_HOST_NAME`, `ORIGIN_CERTIFICATE_SECRET_ID`, `ENTRA_API_CLIENT_ID`, `ENTRA_SPA_CLIENT_ID`. They have the same meaning as in the Azure DevOps table above. Empty lists default to `[]`.

3. Add repository variables: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, and the five values printed by `bootstrap.sh` (`UNIQUE_PREFIX`, `TF_STATE_RESOURCE_GROUP`, `TF_STATE_STORAGE_ACCOUNT`, `ACR_NAME`, `ACR_RESOURCE_GROUP`). An environment can override `AZURE_SUBSCRIPTION_ID` if it uses its own subscription. No secrets are needed.
4. Let the workflows write the `releases` branch. Protect `releases` so that only GitHub Actions can push to it. The workflows use `GITHUB_TOKEN` with `contents: write`, and its pushes do not start other workflows.
5. Register self-hosted runners with the label `fraudshield-private` inside the network (data spoke), with Python 3 and the Azure CLI. The production Databricks workspace is private.

The Terraform plan is uploaded as a one-day artifact, because it can contain sensitive values. Reviewers read the plan summary on the run page before approving the apply.

## Differences from the design, and why

| Design | Here | Why |
|---|---|---|
| Australia East and Southeast; Australian regulation | UK South and UK West; UK obligations to be confirmed | Deployment region changed to the UK. Compliance re-baselines section 12 (FS-6) |
| Java 25 | Java 21 LTS | The toolchain this release was built and tested on |
| Seven services (decision, rules, scoring, case, strategy, copilot, notification) | Four: rules and scoring run inside decision; notification is part of case for now | The design's ADR keeps rules and the model in-process, with no network hop |
| Drools rule engine | Parameterised rule types evaluated in memory | Covers every rule in the design with no rule compiler on the payment path; Drools can replace it behind the same snapshot |
| Angular Material and AG Grid | Custom components on design tokens from the mock-ups | Matches the mock-ups closely and keeps the first screen at 138 kB; can adopt Material later |
| SignalR live updates | 30-second polling | SignalR push is FS-114 |
| Customer-managed keys in Managed HSM | Microsoft-managed keys, plus Key Vault Premium | Managed HSM needs a security-domain ceremony with named key holders (Jira) |
| Front Door to the portal | Front Door, then a regional Application Gateway, then API Management or ingress | API Management in internal mode cannot be a Front Door origin directly |
| mTLS for service mesh traffic | Cilium network policy, with no mesh yet | A mesh adds latency and operations; decide after the Phase 2 latency proof |
| Managed Redis active geo-replication | Configured, but needs testing for velocity counters | Counter semantics across regions must be proven under failover (E15) |

## Local checks

```bash
cd services && ./mvnw verify
cd portal && CI=1 npx ng test --watch=false && npx ng build
cd infra/terraform && terraform init -backend=false && terraform validate
helm lint deploy/helm/fraudshield-service -f deploy/gitops/services/decision.yaml -f deploy/gitops/ci-fixture.yaml
cd delivery && python generate_backlog.py
```