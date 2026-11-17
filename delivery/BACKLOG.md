# FraudShield backlog

Generated from `backlog_data.py`. Edit that file, then run `py generate_backlog.py`.

Based on the FraudShield Solution Design Document, Draft 1.0 (1 November 2026). Every issue starts in **To Do**: nothing is deployed yet.

## Summary

| Measure | Value |
|---|---|
| Jira project key | FS |
| Epics | 16 |
| Issues | 171 (96 in sprints 1 to 6, 75 future-phase) |
| Points planned in sprints 1 to 6 | 346 |
| Future-phase points (rough) | 434 |
| Points that need roles not yet in the team | 297 |

**Reporter on every ticket:** Yeshwanth Chemakuri (Project Coordinator). Assignees are matched by Jira account email.

## Sprints

Sprints 1 to 6 cover Phase 1 (Foundation) of the design: data access, Azure landing zone, CI/CD, and labelled history.

| Sprint | Dates | Goal | Issues | Points |
|---|---|---|---|---|
| Sprint 1 | 16 Nov 2026 to 27 Nov 2026 | Jira, Azure DevOps, and Azure access in place; open questions raised; state and registry created | 18 | 51 |
| Sprint 2 | 30 Nov 2026 to 11 Dec 2026 | Hub and spokes plus operational stores applied to dev; history profiled | 18 | 64 |
| Sprint 3 | 14 Dec 2026 to 23 Dec 2026 | Dev AKS platform add-ons, lakehouse, and firewall egress | 12 | 38 |
| Sprint 4 | 11 Jan 2027 to 22 Jan 2027 | Test environment, Unity Catalog, edge and APIM networking, first streaming ingest | 19 | 81 |
| Sprint 5 | 25 Jan 2027 to 5 Feb 2027 | Batch ingest, data contracts, mTLS, two-region design | 16 | 60 |
| Sprint 6 | 8 Feb 2027 to 19 Feb 2027 | Silver and Gold, reconciliation, Phase 1 exit gate | 13 | 52 |

Sprint 3 is short for the Christmas shutdown. UK bank holidays are not otherwise deducted.

## Load by person

Points in sprints 1 to 6. Use this to rebalance at sprint planning.

| Person | Role | Jira account | S1 | S2 | S3 | S4 | S5 | S6 | Total | Future |
|---|---|---|---|---|---|---|---|---|---|---|
| Anoop Diddigi Kulkarni | Cloud Data Architect | anoop.kulkarni@zynxtechnologies.com | 20 | 22 | 18 | 28 | 10 | 8 | 106 | 26 |
| Pradip Shrestha | Tester | pradip.shrestha@zynxtechnologies.com | 7 | 9 | 5 | 6 | 6 | 13 | 46 | 44 |
| Priyanka Pothuraju | Data Engineer | priyanka.pothuraju@zynxtechnologies.com | 5 | 5 | 6 | 21 | 13 | 19 | 69 | 44 |
| Yeshwanth Chemakuri | Project Coordinator | yeshwanth.chemakuri@zynxtechnologies.com | 16 | 13 | 4 | 5 | 5 | 5 | 48 | 9 |
| Sri Gowthami Seelam | Network Engineer | srigowthami.seelam@zynxtechnologies.com | 3 | 15 | 5 | 21 | 21 | 5 | 70 | 21 |
| **Not yet in the team** | Java Engineer | unassigned | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 133 |
| **Not yet in the team** | ML Engineer | unassigned | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 64 |
| **Not yet in the team** | Angular Developer | unassigned | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 39 |
| **Not yet in the team** | DevOps Engineer | unassigned | 0 | 0 | 0 | 0 | 0 | 2 | 2 | 23 |
| **Not yet in the team** | Security Engineer | unassigned | 0 | 0 | 0 | 0 | 5 | 0 | 5 | 18 |
| **Not yet in the team** | Model Risk Owner | unassigned | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 5 |
| **Not yet in the team** | Fraud Strategist | unassigned | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 8 |

## Blockers

These stop other work if they slip.

- **2** Request the Azure DevOps project, repository, and Azure subscriptions (Yeshwanth Chemakuri, Sprint 1)
- **20** Design the IP address plan for hub, app spoke, and data spoke in both regions (Sri Gowthami Seelam, Sprint 1)
- **17** Create the fraudshield-api and fraudshield-portal app registrations and app roles (Anoop Diddigi Kulkarni, Sprint 2)
- **35** Set up the releases branch and build service permissions (Anoop Diddigi Kulkarni, Sprint 1)
- **40** Run the bootstrap script: Terraform state storage and geo-replicated registry (Anoop Diddigi Kulkarni, Sprint 1)
- **41** Create the fraudshield-azure service connection with workload identity federation (Anoop Diddigi Kulkarni, Sprint 1)

## Open questions from section 18

| ID | Question | Owner | Sprint |
|---|---|---|---|
| 3 | Confirm the client and payment rails in scope (open question 1) | Yeshwanth Chemakuri | Sprint 1 |
| 4 | Assess available labelled history: 24 months of transactions and outcomes (open question 2) | Anoop Diddigi Kulkarni | Sprint 1 |
| 5 | Confirm device and behaviour signals supplied by the banking app (open question 3) | Yeshwanth Chemakuri | Sprint 2 |
| 6 | Confirm the regulations and contracts that apply (open question 4) | Yeshwanth Chemakuri | Sprint 1 |
| 7 | Agree whether tokenised case data may be sent to the language model (open question 5) | Yeshwanth Chemakuri | Sprint 2 |
| 8 | Build the Phase 1 Azure cost model (open question 6) | Anoop Diddigi Kulkarni | Sprint 2 |

## Epics

| Epic | Name | Issues | Points |
|---|---|---|---|
| E1 | Programme setup and governance | 18 | 50 |
| E2 | Azure landing zone and network | 16 | 61 |
| E3 | CI/CD and DevOps platform | 24 | 81 |
| E4 | Operational data stores | 6 | 21 |
| E5 | Data platform and lakehouse | 15 | 76 |
| E6 | Feature Store and online features | 8 | 49 |
| E7 | Decision core | 13 | 79 |
| E8 | Case management and investigator portal | 11 | 70 |
| E9 | Case Copilot | 5 | 22 |
| E10 | Strategy and rules tooling | 5 | 34 |
| E11 | Models and MLOps | 13 | 77 |
| E12 | Security and compliance | 9 | 42 |
| E13 | Testing and quality | 13 | 48 |
| E14 | Observability and operations | 5 | 21 |
| E15 | Resilience and multi-region | 5 | 28 |
| E16 | Shadow run, rollout, and go-live | 5 | 21 |

## Backlog by epic

### E1: Programme setup and governance

Jira and Azure DevOps setup, open questions from section 18, risks, hiring, stakeholder sign-offs, and phase exit gates.

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 1 | Task | Create the FS Jira project, import this backlog, and set up the board | Yeshwanth Chemakuri | 2 | Highest | Sprint 1 |
| 2 | Task | Request the Azure DevOps project, repository, and Azure subscriptions | Yeshwanth Chemakuri | 2 | Highest | Sprint 1 |
| 3 | Decision | Confirm the client and payment rails in scope (open question 1) | Yeshwanth Chemakuri | 3 | Highest | Sprint 1 |
| 4 | Spike | Assess available labelled history: 24 months of transactions and outcomes (open question 2) | Anoop Diddigi Kulkarni | 8 | Highest | Sprint 1 |
| 5 | Decision | Confirm device and behaviour signals supplied by the banking app (open question 3) | Yeshwanth Chemakuri | 2 | High | Sprint 2 |
| 6 | Decision | Confirm the regulations and contracts that apply (open question 4) | Yeshwanth Chemakuri | 3 | High | Sprint 1 |
| 7 | Decision | Agree whether tokenised case data may be sent to the language model (open question 5) | Yeshwanth Chemakuri | 2 | High | Sprint 2 |
| 8 | Task | Build the Phase 1 Azure cost model (open question 6) | Anoop Diddigi Kulkarni | 5 | High | Sprint 2 |
| 9 | Task | Coordinate hiring for the roles the team is missing | Yeshwanth Chemakuri | 3 | Highest | Sprint 1 |
| 10 | Task | Set up the RAID log with the risks from section 16 | Yeshwanth Chemakuri | 2 | High | Sprint 1 |
| 11 | Task | Agree the segregation-of-duties roster | Yeshwanth Chemakuri | 2 | High | Sprint 3 |
| 12 | Task | Phase 1 exit gate: security and data approval | Yeshwanth Chemakuri | 3 | Highest | Sprint 6 |
| 13 | Task | Set up sprint ceremonies and the stakeholder cadence | Yeshwanth Chemakuri | 1 | Medium | Sprint 1 |
| 14 | Task | Get stakeholder agreement on the proposed non-functional targets | Yeshwanth Chemakuri | 3 | High | Sprint 2 |
| 15 | Task | Coordinate vendor accounts: Microsoft, Databricks, and the ExpressRoute provider | Yeshwanth Chemakuri | 2 | Medium | Sprint 3 |
| 16 | Task | Phase 2 readiness check: hires and partner in place | Yeshwanth Chemakuri | 2 | High | Sprint 6 |
| 17 | Task | Create the fraudshield-api and fraudshield-portal app registrations and app roles | Anoop Diddigi Kulkarni | 3 | Highest | Sprint 2 |
| 18 | Task | Grant Azure SQL access to the case and strategy identities | Anoop Diddigi Kulkarni | 2 | High | Sprint 3 |

### E2: Azure landing zone and network

Hub-and-spoke network in UK South and UK West: Azure Firewall, ExpressRoute, Bastion, Private DNS, Front Door + WAF, Application Gateway, and APIM networking.

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 20 | Task | Design the IP address plan for hub, app spoke, and data spoke in both regions | Sri Gowthami Seelam | 3 | Highest | Sprint 1 |
| 21 | Task | Review and apply the hub-network module to dev | Sri Gowthami Seelam | 5 | Highest | Sprint 2 |
| 22 | Task | Review and apply the app-spoke and data-spoke modules with hub peering | Sri Gowthami Seelam | 5 | Highest | Sprint 2 |
| 23 | Task | Link Private DNS zones for all private endpoints | Sri Gowthami Seelam | 3 | High | Sprint 2 |
| 24 | Task | Route spoke egress through Azure Firewall | Sri Gowthami Seelam | 5 | High | Sprint 3 |
| 25 | Spike | Validate Databricks VNet-injection subnet sizing | Sri Gowthami Seelam | 2 | Medium | Sprint 2 |
| 26 | Task | Order ExpressRoute circuits for both regions | Yeshwanth Chemakuri | 3 | High | Sprint 2 |
| 27 | Task | Design ExpressRoute connectivity to the payment switch and core banking | Sri Gowthami Seelam | 5 | High | Sprint 4 |
| 28 | Task | Apply the edge module: Front Door Premium and WAF for the portal | Sri Gowthami Seelam | 5 | High | Sprint 4 |
| 29 | Task | Configure Application Gateway and APIM in internal mode | Sri Gowthami Seelam | 5 | High | Sprint 4 |
| 30 | Task | Set up mTLS for payment channels at API Management | Sri Gowthami Seelam | 5 | High | Sprint 5 |
| 31 | Task | Set up Private Link connectivity from the payment switch | Sri Gowthami Seelam | 3 | Medium | Sprint 5 |
| 32 | Task | Apply the landing zone to the test environment | Sri Gowthami Seelam | 3 | High | Sprint 4 |
| 33 | Task | Baseline NSGs and VNet flow logs across spokes | Sri Gowthami Seelam | 3 | Medium | Sprint 5 |
| 34 | Task | Assign Azure Policy guardrails for the landing zone | Anoop Diddigi Kulkarni | 3 | Medium | Sprint 5 |
| 37 | Task | Issue the portal origin certificate and enable the edge | Sri Gowthami Seelam | 3 | High | Sprint 4 |

### E3: CI/CD and DevOps platform

Azure DevOps pipelines for infrastructure, cluster add-ons, application images, GitOps promotion, Databricks bundles, and model releases.

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 19 | Task | Deploy the first-release services and portal to dev | Anoop Diddigi Kulkarni | 3 | High | Sprint 4 |
| 35 | Task | Set up the releases branch and build service permissions | Anoop Diddigi Kulkarni | 2 | Highest | Sprint 1 |
| 36 | Task | Create the fraudshield-private agent pool inside the network | Sri Gowthami Seelam | 5 | High | Sprint 5 |
| 38 | Task | Record AKS_CLUSTERS and GITOPS_REPO_TOKEN in each environment variable group | Anoop Diddigi Kulkarni | 1 | High | Sprint 2 |
| 40 | Task | Run the bootstrap script: Terraform state storage and geo-replicated registry | Anoop Diddigi Kulkarni | 2 | Highest | Sprint 1 |
| 41 | Task | Create the fraudshield-azure service connection with workload identity federation | Anoop Diddigi Kulkarni | 3 | Highest | Sprint 1 |
| 42 | Task | Create variable groups and environments with approvals | Anoop Diddigi Kulkarni | 2 | Highest | Sprint 1 |
| 43 | Task | Make ci.yml a required check on main | Anoop Diddigi Kulkarni | 2 | Highest | Sprint 1 |
| 44 | Task | Add branch policy, PR template, and CODEOWNERS | Anoop Diddigi Kulkarni | 1 | Medium | Sprint 1 |
| 45 | Task | First run of infra.yml to dev, and fix what the first plan finds | Anoop Diddigi Kulkarni | 8 | Highest | Sprint 2 |
| 46 | Task | Install cluster add-ons on dev AKS with platform.yml | Anoop Diddigi Kulkarni | 5 | High | Sprint 3 |
| 47 | Task | Apply the Argo CD AppProject and ApplicationSet | Anoop Diddigi Kulkarni | 3 | High | Sprint 3 |
| 48 | Task | Deploy the Databricks Asset Bundle to dev | Priyanka Pothuraju | 3 | High | Sprint 3 |
| 49 | Task | Prove app.yml end to end with a placeholder service | Anoop Diddigi Kulkarni | 5 | High | Sprint 4 |
| 50 | Task | Promote infrastructure and the placeholder service to test | Anoop Diddigi Kulkarni | 3 | High | Sprint 4 |
| 51 | Task | Define pipeline quality gates | Pradip Shrestha | 3 | High | Sprint 2 |
| 52 | Task | Add SonarQube or GitHub Advanced Security to ci.yml | Anoop Diddigi Kulkarni | 3 | Medium | Sprint 4 |
| 53 | Task | Write post-deployment smoke tests | Pradip Shrestha | 3 | High | Sprint 4 |
| 54 | Task | Test the CI gates with failing pull requests | Pradip Shrestha | 2 | Medium | Sprint 1 |
| 55 | Task | Pin tool versions and add pipeline caching | Anoop Diddigi Kulkarni | 2 | Low | Sprint 5 |
| 56 | Task | Hand pipeline ownership to the DevOps engineers | DevOps Engineer *(to fill)* | 2 | Medium | Sprint 6 |
| 57 | Task | Configure model-release.yml promotion gates | Anoop Diddigi Kulkarni | 5 | High | Phase 3 |
| 58 | Task | Apply perf infrastructure in two regions | Anoop Diddigi Kulkarni | 5 | High | Phase 5 |
| 59 | Task | First production infrastructure apply | DevOps Engineer *(to fill)* | 8 | Highest | Phase 5 |

### E4: Operational data stores

Azure Managed Redis, Cosmos DB, Azure SQL, and Event Hubs for the decision path.

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 60 | Task | Review and apply the operational-data module to dev | Anoop Diddigi Kulkarni | 5 | Highest | Sprint 2 |
| 61 | Task | Design Cosmos DB containers and partition keys | Anoop Diddigi Kulkarni | 3 | High | Sprint 3 |
| 62 | Task | Create Event Hubs, consumer groups, and capture settings | Priyanka Pothuraju | 3 | High | Sprint 3 |
| 63 | Task | Define the decision event schema in Schema Registry | Priyanka Pothuraju | 5 | High | Sprint 4 |
| 64 | Task | Size Redis and set the eviction policy for velocity counters | Anoop Diddigi Kulkarni | 2 | Medium | Sprint 5 |
| 65 | Task | Set up Azure SQL database roles and the migration identity | Anoop Diddigi Kulkarni | 3 | Medium | Sprint 5 |

### E5: Data platform and lakehouse

ADLS Gen2, Delta Lake Bronze/Silver/Gold, Data Factory, Databricks, Unity Catalog, Purview, tokenisation, and data quality (FR-9).

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 70 | Task | Review and apply the lakehouse module to dev | Anoop Diddigi Kulkarni | 5 | Highest | Sprint 3 |
| 71 | Task | Set up the Unity Catalog metastore, catalogs, and access model | Anoop Diddigi Kulkarni | 5 | Highest | Sprint 4 |
| 72 | Spike | Profile the historical data for the label assessment | Priyanka Pothuraju | 5 | Highest | Sprint 2 |
| 73 | Task | Build a synthetic data generator for dev | Priyanka Pothuraju | 5 | High | Sprint 1 |
| 74 | Story | Stream decision events from Event Hubs into Bronze | Priyanka Pothuraju | 8 | High | Sprint 4 |
| 75 | Story | Tokenise card and account identifiers before Silver | Priyanka Pothuraju | 8 | Highest | Sprint 4 |
| 76 | Story | Ingest core banking transactions with Data Factory | Priyanka Pothuraju | 5 | High | Sprint 5 |
| 77 | Story | Ingest chargebacks with Data Factory | Priyanka Pothuraju | 3 | High | Sprint 5 |
| 78 | Story | Enforce schema contracts at ingestion with a quarantine table | Priyanka Pothuraju | 5 | High | Sprint 5 |
| 79 | Story | Capture case outcomes from Azure SQL with CDC | Priyanka Pothuraju | 5 | Medium | Sprint 6 |
| 80 | Story | Reconcile daily decision counts across Cosmos DB, Event Hubs, and Bronze | Priyanka Pothuraju | 3 | High | Sprint 6 |
| 81 | Story | Build Silver conformed transactions and the Gold labels mart | Priyanka Pothuraju | 8 | High | Sprint 6 |
| 82 | Story | Ingest device and login events from Event Hubs | Priyanka Pothuraju | 3 | Medium | Sprint 6 |
| 83 | Task | Set up Purview scans and lineage for ADLS and Unity Catalog | Anoop Diddigi Kulkarni | 3 | Medium | Sprint 6 |
| 84 | Story | Publish Power BI fraud reports through Databricks SQL | Priyanka Pothuraju | 5 | Medium | Phase 4 |

### E6: Feature Store and online features

Feature groups from section 6, point-in-time training sets, and sync to Redis and Cosmos DB.

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 90 | Task | Design the Feature Store: groups, keys, and point-in-time rules | Anoop Diddigi Kulkarni | 5 | High | Sprint 6 |
| 91 | Story | Build velocity feature definitions for offline training | Priyanka Pothuraju | 5 | High | Phase 3 |
| 92 | Story | Build streaming customer behaviour features | Priyanka Pothuraju | 8 | High | Phase 3 |
| 93 | Story | Build streaming payee features | Priyanka Pothuraju | 5 | High | Phase 3 |
| 94 | Story | Build hourly graph features with GraphFrames | Priyanka Pothuraju | 8 | Medium | Phase 3 |
| 95 | Story | Sync online features to Redis and Cosmos DB | Priyanka Pothuraju | 8 | Highest | Phase 3 |
| 96 | Story | Build point-in-time training sets with label timing | Priyanka Pothuraju | 5 | High | Phase 3 |
| 97 | Task | Test online and offline feature agreement | Pradip Shrestha | 5 | High | Phase 3 |

### E7: Decision core

Decision, rules, and scoring services: p99 under 200 ms, 2,000 TPS sustained and 5,000 at peak, with fallback rules (FR-1, FR-2, FR-3).

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 111 | Story | Replace in-memory velocity and profile stores with Managed Redis and Cosmos DB | Java Engineer *(to fill)* | 8 | Highest | Phase 2 |
| 112 | Story | Replace the in-memory event queue with the Cosmos DB outbox and Event Hubs | Java Engineer *(to fill)* | 8 | Highest | Phase 2 |
| 100 | Spike | Prove the latency budget: Java 25, ZGC, in-process ONNX, p99 under 200 ms | Java Engineer *(to fill)* | 8 | Highest | Phase 2 |
| 101 | Story | Decision API with idempotency keys (FR-1) | Java Engineer *(to fill)* | 8 | Highest | Phase 2 |
| 102 | Story | Absolute rules in Drools: sanctions and blocklists (FR-2) | Java Engineer *(to fill)* | 8 | Highest | Phase 2 |
| 103 | Story | Score with ONNX Runtime and return reason codes (FR-3) | Java Engineer *(to fill)* | 8 | Highest | Phase 2 |
| 104 | Story | Policy thresholds and outcomes | Java Engineer *(to fill)* | 5 | High | Phase 2 |
| 105 | Story | Feature retrieval with a 25 ms timeout and safe defaults | Java Engineer *(to fill)* | 5 | High | Phase 2 |
| 106 | Story | Fallback to rules only when scoring fails | Java Engineer *(to fill)* | 5 | Highest | Phase 2 |
| 107 | Story | Decision outbox in Cosmos DB, published to Event Hubs | Java Engineer *(to fill)* | 5 | High | Phase 2 |
| 108 | Story | Live reload of versioned rules | Java Engineer *(to fill)* | 5 | Medium | Phase 2 |
| 109 | Task | Helm values for the decision, rules, and scoring services | Anoop Diddigi Kulkarni | 3 | High | Phase 2 |
| 110 | Task | Publish the decision API in APIM with throttling and routing | Sri Gowthami Seelam | 3 | High | Phase 2 |

### E8: Case management and investigator portal

Alerts, cases, SLAs, and the Angular portal screens (FR-4, FR-5, FR-6).

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 114 | Story | Push live alert and SLA updates to the portal with Azure SignalR | Angular Developer *(to fill)* | 5 | Medium | Phase 4 |
| 120 | Story | Case service: alerts, queues, assignment, and SLAs (FR-4) | Java Engineer *(to fill)* | 8 | High | Phase 4 |
| 121 | Story | Confirm fraud or release a payment with a required reason (FR-5) | Java Engineer *(to fill)* | 5 | High | Phase 4 |
| 122 | Story | Alert queue screen | Angular Developer *(to fill)* | 8 | High | Phase 4 |
| 123 | Story | Case investigation screen with linked entities (FR-6) | Angular Developer *(to fill)* | 8 | High | Phase 4 |
| 124 | Story | Fraud operations overview dashboard | Angular Developer *(to fill)* | 5 | Medium | Phase 4 |
| 125 | Story | Portal sign-in with MSAL and Entra ID roles | Angular Developer *(to fill)* | 5 | High | Phase 4 |
| 126 | Story | Notification service for alerts and SLA messages | Java Engineer *(to fill)* | 5 | Medium | Phase 4 |
| 127 | Task | Playwright suite for alert handling and case workflows | Pradip Shrestha | 8 | High | Phase 4 |
| 128 | Task | Accessibility testing to WCAG 2.2 AA | Pradip Shrestha | 5 | Medium | Phase 4 |
| 129 | Task | UAT with fraud analysts and team leads | Pradip Shrestha | 8 | High | Phase 4 |

### E9: Case Copilot

Grounded, cited case summaries through Microsoft Foundry, with no decision authority (FR-7).

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 135 | Task | Apply the ai module: Foundry and Content Safety in UK South with a private endpoint | Anoop Diddigi Kulkarni | 3 | High | Phase 4 |
| 136 | Story | Grounded case summaries with citation validation (FR-7) | Java Engineer *(to fill)* | 8 | High | Phase 4 |
| 137 | Task | Build the reviewed Copilot evaluation set | Pradip Shrestha | 5 | High | Phase 4 |
| 138 | Task | Prompt-injection tests with merchant names and payment descriptions | Pradip Shrestha | 3 | High | Phase 4 |
| 139 | Task | Add the Copilot evaluation stage to the pipeline | ML Engineer *(to fill)* | 3 | Medium | Phase 4 |

### E10: Strategy and rules tooling

Rule authoring, backtests, threshold simulation, and two-person approval (FR-8).

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 115 | Story | Connect rule backtests to Databricks SQL | Java Engineer *(to fill)* | 5 | Medium | Phase 4 |
| 145 | Story | Rule authoring and backtests through Databricks SQL (FR-8) | Java Engineer *(to fill)* | 8 | High | Phase 4 |
| 146 | Story | Two-person approval for rules and thresholds | Java Engineer *(to fill)* | 5 | Highest | Phase 4 |
| 147 | Story | Rules and thresholds screen | Angular Developer *(to fill)* | 8 | Medium | Phase 4 |
| 148 | Task | Migrate existing fraud rules into the first Drools ruleset | Fraud Strategist *(to fill)* | 8 | High | Phase 2 |

### E11: Models and MLOps

fs-txn, fs-anomaly, and fs-mule models; MLflow and Unity Catalog registry; promotion gates; monitoring and retraining (FR-10).

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 113 | Story | Build the ONNX scorer with signed-model loading | Java Engineer *(to fill)* | 8 | High | Phase 3 |
| 116 | Task | Write release approvals to the Unity Catalog approval tag | ML Engineer *(to fill)* | 3 | High | Phase 3 |
| 155 | Task | Set up MLflow and the Unity Catalog model registry with Champion and Challenger aliases | Anoop Diddigi Kulkarni | 5 | High | Phase 3 |
| 156 | Story | Train fs-txn with LightGBM and export to ONNX | ML Engineer *(to fill)* | 13 | Highest | Phase 3 |
| 157 | Story | Train fs-anomaly with Isolation Forest | ML Engineer *(to fill)* | 8 | Medium | Phase 3 |
| 158 | Story | Build fs-mule hourly batch scoring | ML Engineer *(to fill)* | 8 | Medium | Phase 3 |
| 159 | Task | Sign ONNX models and store them in immutable storage | ML Engineer *(to fill)* | 3 | High | Phase 3 |
| 160 | Story | SHAP reason codes for alerts outside the decision path | ML Engineer *(to fill)* | 5 | High | Phase 3 |
| 161 | Story | Model monitoring: drift, skew, and performance | ML Engineer *(to fill)* | 8 | High | Phase 3 |
| 162 | Story | Fairness comparison across review groups | ML Engineer *(to fill)* | 5 | High | Phase 3 |
| 163 | Story | Review a random sample of low-scoring payments | ML Engineer *(to fill)* | 3 | Medium | Phase 3 |
| 164 | Task | Model card template and approval process | Model Risk Owner *(to fill)* | 3 | High | Phase 3 |
| 165 | Story | Monthly retraining job that produces a candidate | ML Engineer *(to fill)* | 5 | Medium | Phase 3 |

### E12: Security and compliance

PCI DSS scope, PRA and FCA operational resilience, UK GDPR, PSR APP-scam reimbursement, key management, segregation of duties, and penetration tests.

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 170 | Task | Apply the security module to dev: Key Vault and Defender for Cloud | Anoop Diddigi Kulkarni | 3 | High | Sprint 4 |
| 171 | Task | Agree PCI DSS scope with the tokenisation service owner | Yeshwanth Chemakuri | 3 | High | Sprint 2 |
| 172 | Task | Map controls to PRA and FCA operational resilience | Yeshwanth Chemakuri | 5 | Medium | Sprint 5 |
| 173 | Task | Complete the privacy impact assessment | Yeshwanth Chemakuri | 5 | High | Sprint 4 |
| 174 | Task | Set up workload identity and PIM for admin access | Anoop Diddigi Kulkarni | 3 | High | Sprint 4 |
| 175 | Task | Threat model for the decision path, including reason-code leakage | Security Engineer *(to fill)* | 5 | High | Sprint 5 |
| 176 | Task | Customer-managed keys in Key Vault Managed HSM | Security Engineer *(to fill)* | 5 | High | Phase 2 |
| 177 | Task | Microsoft Sentinel and immutable audit logs | Security Engineer *(to fill)* | 5 | High | Phase 5 |
| 178 | Task | Pre-go-live penetration test, including AI-specific tests | Security Engineer *(to fill)* | 8 | Highest | Phase 5 |

### E13: Testing and quality

Test strategy, environment and data testing, functional and UAT, performance, and contract tests (section 14).

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 39 | Task | Test the portal screens against the design mock-ups with Playwright | Pradip Shrestha | 5 | High | Sprint 6 |
| 185 | Task | Write the FraudShield test strategy and Phase 1 test plan | Pradip Shrestha | 5 | Highest | Sprint 1 |
| 186 | Task | Set up test management in Jira | Pradip Shrestha | 3 | High | Sprint 2 |
| 187 | Task | Test data management: synthetic for dev, tokenised samples for test | Pradip Shrestha | 3 | High | Sprint 2 |
| 188 | Task | Infrastructure validation tests for dev | Pradip Shrestha | 3 | High | Sprint 3 |
| 189 | Task | Test environment readiness checklist | Pradip Shrestha | 2 | Medium | Sprint 3 |
| 190 | Task | Data quality test plan with Great Expectations | Pradip Shrestha | 3 | High | Sprint 4 |
| 191 | Task | Verify Unity Catalog access by role | Pradip Shrestha | 3 | Medium | Sprint 5 |
| 192 | Task | Test ingestion and reconciliation jobs | Pradip Shrestha | 3 | High | Sprint 6 |
| 193 | Task | Build the Gatling load test harness for the decision API | Pradip Shrestha | 5 | High | Sprint 6 |
| 194 | Task | Network connectivity test pack | Pradip Shrestha | 3 | Medium | Sprint 5 |
| 195 | Task | Contract tests for the decision API and event schemas | Pradip Shrestha | 5 | High | Phase 2 |
| 196 | Task | 8-hour soak test at peak load | Pradip Shrestha | 5 | High | Phase 5 |

### E14: Observability and operations

Logs, metrics, traces, alerts, dashboards, ServiceNow, and runbooks.

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 200 | Task | Apply the observability module to dev | Anoop Diddigi Kulkarni | 3 | High | Sprint 4 |
| 201 | Task | Network monitoring: Connection Monitor, firewall logs, and ExpressRoute alerts | Sri Gowthami Seelam | 5 | Medium | Sprint 6 |
| 202 | Task | OpenTelemetry collector and Grafana dashboards | DevOps Engineer *(to fill)* | 5 | High | Phase 2 |
| 203 | Task | ServiceNow incident and change integration | Yeshwanth Chemakuri | 3 | Medium | Phase 5 |
| 204 | Task | Runbooks and on-call for the decision path and model incidents | DevOps Engineer *(to fill)* | 5 | High | Phase 5 |

### E15: Resilience and multi-region

Active-active decisions in two regions, failover under 5 minutes with no accepted decisions lost, and Chaos Studio tests.

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 210 | Task | Design active-active decision traffic across two regions | Sri Gowthami Seelam | 5 | High | Sprint 5 |
| 211 | Task | Test Cosmos DB multi-region writes and the SQL failover group | Sri Gowthami Seelam | 5 | High | Phase 5 |
| 212 | Task | Chaos Studio experiments: lose the feature store, a region, and Event Hubs | Sri Gowthami Seelam | 8 | High | Phase 5 |
| 213 | Task | Regional failover test under 5 minutes | Sri Gowthami Seelam | 5 | Highest | Phase 5 |
| 214 | Task | Lakehouse recovery: ADLS redundancy and Databricks workspace rebuild | Anoop Diddigi Kulkarni | 5 | Medium | Phase 5 |

### E16: Shadow run, rollout, and go-live

Full-volume shadow run, go-live criteria, and phased rollout one channel at a time (phases 5 and 6).

| ID | Type | Summary | Owner | Points | Priority | Sprint or phase |
|---|---|---|---|---|---|---|
| 220 | Task | Run full-volume shadow traffic for four weeks | Java Engineer *(to fill)* | 8 | Highest | Phase 5 |
| 221 | Task | Go-live readiness review | Yeshwanth Chemakuri | 3 | Highest | Phase 5 |
| 222 | Task | Plan the phased rollout one channel at a time, starting with step-up only | Yeshwanth Chemakuri | 3 | High | Phase 6 |
| 223 | Task | Risk owner sign-off for each channel | Model Risk Owner *(to fill)* | 2 | Highest | Phase 6 |
| 224 | Task | Production release of the first channel | DevOps Engineer *(to fill)* | 5 | Highest | Phase 6 |

## Future roadmap

Work after Phase 1, from section 15 of the design. Points are rough and most of it needs the hires in section 15.

| Phase | Name | Months | Outcome and exit gate | Items | Rough points |
|---|---|---|---|---|---|
| 2 | Decision core | Months 3-6 | Decision API, rules, and fallback running in shadow; latency target proven | 17 | 102 |
| 3 | First models | Months 5-8 | Feature Store, fs-txn, and MLOps pipeline; beats current rules offline | 21 | 126 |
| 4 | Investigator portal | Months 6-9 | Queue, cases, strategy tools, and Copilot pilot; analyst acceptance | 21 | 123 |
| 5 | Shadow and hardening | Months 9-11 | Full-volume shadow, load, chaos, and security tests; go-live criteria met | 13 | 73 |
| 6 | Phased rollout | Months 11-13 | One channel at a time, step-up first; risk owner sign-off | 3 | 10 |

## Definition of done

- Reviewed by someone other than the author (segregation of duties applies to rules, models, and production changes)
- Changes made through a pull request, and ci.yml passes
- Infrastructure changed only through Terraform and infra.yml, never by hand in the portal
- Deployed to dev, and checked by the tester
- No card numbers, account identifiers, or customer data in logs, tickets, or screenshots
- README or runbook updated where behaviour changes

## Importing into Jira

1. Create the Jira project with key `FS` (Scrum template). Ask a Jira admin to confirm the Story Points and Sprint fields are on the Story and Task screens.
2. Optional: create Sprint 1 to Sprint 6 on the board first, with the dates above. If you skip this, the importer creates sprints by name.
3. Go to **Settings > System > External system import > CSV**, choose `jira-import.csv`, and pick the FS project. File encoding is UTF-8.
4. Map the columns:
   - **Issue Id** to Issue Id, and **Parent** to Parent. This links each story and task to its epic (E1 to E16).
   - **Issue Type**, **Summary**, **Description**, **Priority**, **Status** to the fields of the same name.
   - **Assignee** and **Reporter** to the user fields. They hold Jira account emails, so they match automatically.
   - **Story Points** to your story point field (Story point estimate in team-managed projects).
   - **Sprint** to Sprint. Future-phase issues have no sprint and land in the backlog.
   - Every **Labels** column to Labels. Jira joins the repeated columns.
5. Check the status mapping: To Do must match your workflow.
6. After import, filter `labels in (role-java-engineer, role-ml-engineer, role-angular-developer, role-devops-engineer, role-security-engineer, role-model-risk-owner, role-fraud-strategist)` to find work waiting for new hires.
