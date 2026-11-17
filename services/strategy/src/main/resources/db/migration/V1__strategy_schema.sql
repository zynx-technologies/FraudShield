-- Strategy, rules, thresholds, and model release governance (design sections 4, 5, and 8).

-- Every approved change to rules, blocklists, or thresholds creates a new ruleset version (for example rs-204).
-- The decision service reports this version with every decision, so decisions can be replayed.
CREATE TABLE ruleset_version (
    id         INT IDENTITY(200, 1) NOT NULL PRIMARY KEY,
    created_at DATETIME2      NOT NULL,
    reason     NVARCHAR(300)  NOT NULL
);

CREATE SEQUENCE rule_seq START WITH 101 INCREMENT BY 1;

CREATE TABLE rule (
    id              VARCHAR(20)    NOT NULL PRIMARY KEY,
    name            NVARCHAR(120)  NOT NULL,
    rule_type       VARCHAR(40)    NOT NULL,
    action          VARCHAR(12)    NOT NULL,      -- BLOCK, HOLD, STEP_UP, MONITOR
    status          VARCHAR(12)    NOT NULL,      -- REVIEW (never approved), LIVE, SHADOW, RETIRED
    params          NVARCHAR(2000) NOT NULL,      -- JSON
    current_version INT            NULL,
    created_by      NVARCHAR(200)  NOT NULL,
    created_at      DATETIME2      NOT NULL
);

-- Rule definitions and history. Author and approver must differ.
CREATE TABLE rule_version (
    id              BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    rule_id         VARCHAR(20)    NOT NULL REFERENCES rule (id),
    version         INT            NOT NULL,
    name            NVARCHAR(120)  NOT NULL,
    action          VARCHAR(12)    NOT NULL,
    mode            VARCHAR(12)    NOT NULL,      -- LIVE, SHADOW, RETIRED
    params          NVARCHAR(2000) NOT NULL,
    author          NVARCHAR(200)  NOT NULL,
    created_at      DATETIME2      NOT NULL,
    status          VARCHAR(12)    NOT NULL,      -- PENDING, APPROVED, REJECTED
    decided_by      NVARCHAR(200)  NULL,
    decided_at      DATETIME2      NULL,
    decision_reason NVARCHAR(500)  NULL,
    CONSTRAINT uq_rule_version UNIQUE (rule_id, version)
);

-- Precision and baseline hit counts from the lakehouse rule-performance mart.
CREATE TABLE rule_performance (
    rule_id         VARCHAR(20)   NOT NULL PRIMARY KEY REFERENCES rule (id),
    hits_7d_offline BIGINT        NOT NULL,
    precision_pct   DECIMAL(5, 1) NULL,
    measured_at     DATETIME2     NOT NULL
);

-- Live hit counts reported by the decision service.
CREATE TABLE rule_hit_daily (
    rule_id VARCHAR(20) NOT NULL,
    hit_day DATE        NOT NULL,
    hits    BIGINT      NOT NULL,
    PRIMARY KEY (rule_id, hit_day)
);

-- Absolute rules (FR-2): sanctioned payees and blocked devices.
CREATE TABLE blocklist_entry (
    kind     VARCHAR(12)   NOT NULL,              -- PAYEE, DEVICE
    entry_value VARCHAR(64) NOT NULL,
    source   NVARCHAR(100) NOT NULL,
    added_by NVARCHAR(200) NOT NULL,
    added_at DATETIME2     NOT NULL,
    PRIMARY KEY (kind, entry_value)
);

-- Score thresholds. One ACTIVE row; changes are PENDING until a second person approves them.
CREATE TABLE threshold_policy (
    id              BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    step_up_from    INT            NOT NULL,
    hold_from       INT            NOT NULL,
    block_from      INT            NOT NULL,
    status          VARCHAR(12)    NOT NULL,      -- ACTIVE, PENDING, SUPERSEDED, REJECTED
    reason          NVARCHAR(500)  NOT NULL,
    author          NVARCHAR(200)  NOT NULL,
    created_at      DATETIME2      NOT NULL,
    decided_by      NVARCHAR(200)  NULL,
    decided_at      DATETIME2      NULL,
    decision_reason NVARCHAR(500)  NULL
);

-- Scored decisions over the last 30 days, by score, with confirmed outcomes. Refreshed daily from the
-- lakehouse Gold layer. The threshold simulator reads it.
CREATE TABLE score_distribution (
    score         INT            NOT NULL PRIMARY KEY,
    genuine_count BIGINT         NOT NULL,
    fraud_count   BIGINT         NOT NULL,
    fraud_value   DECIMAL(18, 2) NOT NULL,
    window_days   INT            NOT NULL,
    refreshed_at  DATETIME2      NOT NULL
);

-- Model registry mirror, from MLflow and Unity Catalog.
CREATE TABLE model_version (
    model          VARCHAR(40)   NOT NULL,
    version        INT           NOT NULL,
    stage          VARCHAR(12)   NOT NULL,        -- CANDIDATE, SHADOW, CHAMPION, ARCHIVED
    training_from  DATE          NULL,
    training_to    DATE          NULL,
    pr_auc         DECIMAL(5, 3) NULL,          -- validation, at training time
    live_pr_auc    DECIMAL(5, 3) NULL,          -- on labelled live decisions, last 30 days
    recall_pct     DECIMAL(5, 1) NULL,
    latency_p99_ms INT           NULL,
    owner          NVARCHAR(100) NOT NULL,
    trained_by     NVARCHAR(200) NOT NULL,
    deployed_at    DATETIME2     NULL,
    PRIMARY KEY (model, version)
);

-- From Lakehouse Monitoring: recall at the fixed alert rate on matured labels, by week.
CREATE TABLE model_metric_weekly (
    model      VARCHAR(40)   NOT NULL,
    version    INT           NOT NULL,
    week_start DATE          NOT NULL,
    recall_pct DECIMAL(5, 1) NOT NULL,
    PRIMARY KEY (model, version, week_start)
);

CREATE TABLE feature_drift (
    model       VARCHAR(40)   NOT NULL,
    feature     VARCHAR(60)   NOT NULL,
    psi         DECIMAL(6, 3) NOT NULL,
    measured_at DATETIME2     NOT NULL,
    PRIMARY KEY (model, feature)
);

-- Release approval (screen 6). The approver must be neither the model's author nor the requester.
CREATE TABLE release_request (
    id              BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    model           VARCHAR(40)    NOT NULL,
    version         INT            NOT NULL,
    requested_by    NVARCHAR(200)  NOT NULL,
    requested_at    DATETIME2      NOT NULL,
    status          VARCHAR(12)    NOT NULL,      -- PENDING, APPROVED, REJECTED
    rollback_to     INT            NULL,
    model_card_url  NVARCHAR(500)  NULL,
    lineage_url     NVARCHAR(500)  NULL,
    decided_by      NVARCHAR(200)  NULL,
    decided_at      DATETIME2      NULL,
    decision_reason NVARCHAR(500)  NULL
);

CREATE TABLE release_gate (
    release_id BIGINT        NOT NULL REFERENCES release_request (id),
    seq        INT           NOT NULL,
    gate       NVARCHAR(120) NOT NULL,
    result     NVARCHAR(200) NOT NULL,
    status     VARCHAR(12)   NOT NULL,            -- PASSED, PENDING, FAILED
    PRIMARY KEY (release_id, seq)
);