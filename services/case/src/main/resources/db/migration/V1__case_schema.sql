-- Case management (design section 5, case data model). Works on Azure SQL and on H2 for local runs.

CREATE SEQUENCE alert_seq START WITH 91000 INCREMENT BY 1;
CREATE SEQUENCE case_seq START WITH 21000 INCREMENT BY 1;

-- Groups related alerts. A case cannot close without an outcome.
CREATE TABLE fraud_case (
    id            VARCHAR(20)   NOT NULL PRIMARY KEY,
    account_token VARCHAR(64)   NOT NULL,
    status        VARCHAR(12)   NOT NULL,          -- OPEN, ESCALATED, CLOSED
    assigned_to   NVARCHAR(200) NULL,
    created_at    DATETIME2     NOT NULL,
    closed_at     DATETIME2     NULL
);
CREATE INDEX ix_case_account ON fraud_case (account_token, status);

-- One alert per suspicious decision. References the immutable decision ID.
CREATE TABLE alert (
    id               VARCHAR(20)    NOT NULL PRIMARY KEY,
    case_id          VARCHAR(20)    NOT NULL REFERENCES fraud_case (id),
    decision_id      VARCHAR(40)    NOT NULL,
    transaction_id   VARCHAR(64)    NOT NULL,
    channel          VARCHAR(12)    NOT NULL,
    amount           DECIMAL(15, 2) NOT NULL,
    currency         CHAR(3)        NOT NULL,
    account_token    VARCHAR(64)    NOT NULL,
    payee_token      VARCHAR(64)    NULL,
    device_id        VARCHAR(64)    NULL,
    score            INT            NOT NULL,
    outcome          VARCHAR(12)    NOT NULL,      -- STEP_UP, HOLD, BLOCK
    main_reason      NVARCHAR(200)  NOT NULL,
    model_version    VARCHAR(60)    NULL,
    ruleset_version  VARCHAR(40)    NULL,
    latency_ms       INT            NULL,
    reasons          NVARCHAR(4000) NOT NULL,      -- JSON
    rule_hits        NVARCHAR(4000) NOT NULL,      -- JSON
    features         NVARCHAR(4000) NULL,          -- JSON
    status           VARCHAR(12)    NOT NULL,      -- OPEN, CLOSED
    assigned_to      NVARCHAR(200)  NULL,
    created_at       DATETIME2      NOT NULL,
    sla_due_at       DATETIME2      NOT NULL,
    CONSTRAINT uq_alert_decision UNIQUE (decision_id)
);
CREATE INDEX ix_alert_queue ON alert (status, score, sla_due_at);
CREATE INDEX ix_alert_case ON alert (case_id);
CREATE INDEX ix_alert_payee ON alert (payee_token);
CREATE INDEX ix_alert_device ON alert (device_id);

-- Actions and notes history. Append-only (enforced by a trigger on Azure SQL).
CREATE TABLE case_event (
    id         BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    case_id    VARCHAR(20)    NOT NULL REFERENCES fraud_case (id),
    at         DATETIME2      NOT NULL,
    actor      NVARCHAR(200)  NOT NULL,
    event_type VARCHAR(30)    NOT NULL,
    detail     NVARCHAR(1000) NOT NULL
);
CREATE INDEX ix_case_event_case ON case_event (case_id, at);

-- Confirmed fraud, genuine, or unknown. Feeds training labels (FR-9). Corrections add a new row.
CREATE TABLE outcome (
    id             BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    case_id        VARCHAR(20)   NOT NULL REFERENCES fraud_case (id),
    alert_id       VARCHAR(20)   NOT NULL REFERENCES alert (id),
    transaction_id VARCHAR(64)   NOT NULL,
    label          VARCHAR(20)   NOT NULL,         -- CONFIRMED_FRAUD, GENUINE, UNKNOWN
    reason         NVARCHAR(500) NOT NULL,
    decided_by     NVARCHAR(200) NOT NULL,
    decided_at     DATETIME2     NOT NULL
);
CREATE INDEX ix_outcome_decided ON outcome (decided_at);

-- Hourly decision counts from the decision service, for the operations overview.
CREATE TABLE decision_hourly (
    hour_start       DATETIME2   NOT NULL,
    channel          VARCHAR(12) NOT NULL,
    outcome          VARCHAR(12) NOT NULL,
    decisions        BIGINT      NOT NULL,
    latency_ms_total BIGINT      NOT NULL,
    latency_ms_max   BIGINT      NOT NULL,
    PRIMARY KEY (hour_start, channel, outcome)
);

-- Links between accounts, devices, payees, and network addresses, from the hourly graph job (FR-6).
CREATE TABLE entity_link (
    id        BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    from_type VARCHAR(12) NOT NULL,                -- ACCOUNT, DEVICE, PAYEE, IP
    from_id   VARCHAR(64) NOT NULL,
    to_type   VARCHAR(12) NOT NULL,
    to_id     VARCHAR(64) NOT NULL,
    relation  VARCHAR(30) NOT NULL,
    flagged   BIT         NOT NULL
);
CREATE INDEX ix_entity_link_from ON entity_link (from_id);
CREATE INDEX ix_entity_link_to ON entity_link (to_id);