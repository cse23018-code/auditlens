-- =====================================================================
-- AuditLens :: Forensic Transaction Audit & Risk Analytics Platform
-- Relational schema (ANSI SQL, H2 / PostgreSQL compatible)
-- =====================================================================

DROP VIEW  IF EXISTS v_finding_summary;
DROP VIEW  IF EXISTS v_monthly_spend;
DROP VIEW  IF EXISTS v_vendor_spend;
DROP TABLE IF EXISTS audit_findings;
DROP TABLE IF EXISTS audit_runs;
DROP TABLE IF EXISTS vendor_bank_changes;
DROP TABLE IF EXISTS transactions;
DROP TABLE IF EXISTS employees;
DROP TABLE IF EXISTS vendors;

-- ---------------------------------------------------------------------
-- Master data
-- ---------------------------------------------------------------------
CREATE TABLE vendors (
    vendor_id      INT          PRIMARY KEY,
    vendor_code    VARCHAR(16)  NOT NULL UNIQUE,
    name           VARCHAR(120) NOT NULL,
    category       VARCHAR(48)  NOT NULL,
    country        VARCHAR(48)  NOT NULL,
    tax_id         VARCHAR(24)  NOT NULL,
    bank_account   VARCHAR(24)  NOT NULL,
    onboarded_on   DATE         NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_vendor_status CHECK (status IN ('ACTIVE','BLOCKED','DORMANT'))
);

CREATE TABLE employees (
    employee_id    INT          PRIMARY KEY,
    employee_code  VARCHAR(16)  NOT NULL UNIQUE,
    name           VARCHAR(120) NOT NULL,
    department     VARCHAR(48)  NOT NULL,
    job_role       VARCHAR(48)  NOT NULL,
    approval_limit DECIMAL(14,2) NOT NULL,
    CONSTRAINT ck_emp_limit CHECK (approval_limit >= 0)
);

-- ---------------------------------------------------------------------
-- Accounts-payable transaction ledger (the population under audit)
-- ---------------------------------------------------------------------
CREATE TABLE transactions (
    txn_id         INT           PRIMARY KEY,
    txn_ref        VARCHAR(24)   NOT NULL UNIQUE,
    vendor_id      INT           NOT NULL,
    invoice_no     VARCHAR(32)   NOT NULL,
    amount         DECIMAL(14,2) NOT NULL,
    currency       CHAR(3)       NOT NULL DEFAULT 'INR',
    invoice_date   DATE          NOT NULL,
    posted_at      TIMESTAMP     NOT NULL,
    entered_by     INT           NOT NULL,
    approved_by    INT           NOT NULL,
    cost_center    VARCHAR(16)   NOT NULL,
    gl_account     VARCHAR(16)   NOT NULL,
    payment_method VARCHAR(16)   NOT NULL,
    description    VARCHAR(200),
    status         VARCHAR(16)   NOT NULL DEFAULT 'POSTED',
    CONSTRAINT fk_txn_vendor   FOREIGN KEY (vendor_id)   REFERENCES vendors(vendor_id),
    CONSTRAINT fk_txn_enterer  FOREIGN KEY (entered_by)  REFERENCES employees(employee_id),
    CONSTRAINT fk_txn_approver FOREIGN KEY (approved_by) REFERENCES employees(employee_id),
    CONSTRAINT ck_txn_amount   CHECK (amount > 0),
    CONSTRAINT ck_txn_status   CHECK (status IN ('POSTED','PAID','HELD','REVERSED'))
);

CREATE INDEX idx_txn_vendor       ON transactions(vendor_id);
CREATE INDEX idx_txn_invoice_date ON transactions(invoice_date);
CREATE INDEX idx_txn_amount       ON transactions(amount);
CREATE INDEX idx_txn_vendor_date  ON transactions(vendor_id, invoice_date);

-- ---------------------------------------------------------------------
-- Vendor master-data change log (bank detail tampering is a classic fraud vector)
-- ---------------------------------------------------------------------
CREATE TABLE vendor_bank_changes (
    change_id      INT          PRIMARY KEY,
    vendor_id      INT          NOT NULL,
    changed_on     DATE         NOT NULL,
    old_account    VARCHAR(24)  NOT NULL,
    new_account    VARCHAR(24)  NOT NULL,
    changed_by     INT          NOT NULL,
    CONSTRAINT fk_bank_vendor FOREIGN KEY (vendor_id)  REFERENCES vendors(vendor_id),
    CONSTRAINT fk_bank_user   FOREIGN KEY (changed_by) REFERENCES employees(employee_id)
);

CREATE INDEX idx_bank_vendor_date ON vendor_bank_changes(vendor_id, changed_on);

-- ---------------------------------------------------------------------
-- Audit engine output
-- ---------------------------------------------------------------------
CREATE TABLE audit_runs (
    run_id          INT       PRIMARY KEY,
    started_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP,
    txns_scanned    INT       NOT NULL DEFAULT 0,
    findings_count  INT       NOT NULL DEFAULT 0,
    duration_ms     BIGINT    NOT NULL DEFAULT 0,
    engine_version  VARCHAR(16) NOT NULL
);

CREATE TABLE audit_findings (
    finding_id      INT           PRIMARY KEY,
    run_id          INT           NOT NULL,
    rule_code       VARCHAR(12)   NOT NULL,
    rule_title      VARCHAR(120)  NOT NULL,
    control_domain  VARCHAR(48)   NOT NULL,
    severity        VARCHAR(12)   NOT NULL,
    risk_score      INT           NOT NULL,
    vendor_id       INT,
    txn_id          INT,
    exposure_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    detected_at     TIMESTAMP     NOT NULL,
    summary         VARCHAR(300)  NOT NULL,
    evidence        VARCHAR(1000),
    CONSTRAINT fk_find_run    FOREIGN KEY (run_id)    REFERENCES audit_runs(run_id),
    CONSTRAINT fk_find_vendor FOREIGN KEY (vendor_id) REFERENCES vendors(vendor_id),
    CONSTRAINT ck_find_sev    CHECK (severity IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    CONSTRAINT ck_find_score  CHECK (risk_score BETWEEN 0 AND 100)
);

CREATE INDEX idx_find_run      ON audit_findings(run_id);
CREATE INDEX idx_find_rule     ON audit_findings(rule_code);
CREATE INDEX idx_find_severity ON audit_findings(severity);
CREATE INDEX idx_find_vendor   ON audit_findings(vendor_id);

-- ---------------------------------------------------------------------
-- Reporting views
-- ---------------------------------------------------------------------
CREATE VIEW v_vendor_spend AS
SELECT  v.vendor_id,
        v.vendor_code,
        v.name              AS vendor_name,
        v.category,
        v.country,
        COUNT(t.txn_id)     AS txn_count,
        COALESCE(SUM(t.amount), 0) AS total_spend,
        COALESCE(AVG(t.amount), 0) AS avg_amount,
        MAX(t.invoice_date) AS last_invoice_date
FROM        vendors v
LEFT JOIN   transactions t ON t.vendor_id = v.vendor_id
GROUP BY    v.vendor_id, v.vendor_code, v.name, v.category, v.country;

CREATE VIEW v_monthly_spend AS
SELECT  FORMATDATETIME(t.invoice_date, 'yyyy-MM') AS period,
        COUNT(*)     AS txn_count,
        SUM(t.amount) AS total_spend
FROM    transactions t
GROUP BY FORMATDATETIME(t.invoice_date, 'yyyy-MM');

CREATE VIEW v_finding_summary AS
SELECT  f.rule_code,
        f.rule_title,
        f.control_domain,
        f.severity,
        COUNT(*)                AS finding_count,
        SUM(f.exposure_amount)  AS total_exposure
FROM    audit_findings f
GROUP BY f.rule_code, f.rule_title, f.control_domain, f.severity;
