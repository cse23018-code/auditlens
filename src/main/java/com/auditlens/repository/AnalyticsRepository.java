package com.auditlens.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Reporting queries behind the dashboard. Every aggregate is computed in SQL
 * rather than in application code, so the database does the work it is good at
 * and the API layer stays a thin pass-through.
 */
@Repository
public class AnalyticsRepository {

    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Headline KPIs for the top of the dashboard. */
    public Map<String, Object> headline() {
        return jdbc.queryForMap("""
                SELECT  (SELECT COUNT(*)               FROM transactions)    AS txn_count,
                        (SELECT COALESCE(SUM(amount),0) FROM transactions)   AS total_spend,
                        (SELECT COUNT(*)               FROM vendors)         AS vendor_count,
                        (SELECT COUNT(*)               FROM audit_findings)  AS finding_count,
                        (SELECT COUNT(*)               FROM audit_findings
                          WHERE severity = 'CRITICAL')                       AS critical_count,
                        (SELECT COUNT(*)               FROM audit_findings
                          WHERE severity = 'HIGH')                           AS high_count,
                        (SELECT COALESCE(SUM(exposure_amount),0)
                           FROM audit_findings)                              AS total_exposure,
                        (SELECT COUNT(DISTINCT vendor_id) FROM audit_findings
                          WHERE vendor_id IS NOT NULL)                       AS flagged_vendors
                """);
    }

    /** Finding counts and exposure grouped by control, for the rule breakdown chart. */
    public List<Map<String, Object>> findingsByRule() {
        return jdbc.queryForList("""
                SELECT  rule_code,
                        rule_title,
                        control_domain,
                        severity,
                        COUNT(*)                       AS finding_count,
                        COALESCE(SUM(exposure_amount),0) AS exposure,
                        ROUND(AVG(risk_score), 1)      AS avg_risk_score
                FROM        audit_findings
                GROUP BY    rule_code, rule_title, control_domain, severity
                ORDER BY    finding_count DESC
                """);
    }

    public List<Map<String, Object>> findingsBySeverity() {
        return jdbc.queryForList("""
                SELECT  severity,
                        COUNT(*)                         AS finding_count,
                        COALESCE(SUM(exposure_amount),0) AS exposure
                FROM        audit_findings
                GROUP BY    severity
                ORDER BY    CASE severity
                                WHEN 'CRITICAL' THEN 1
                                WHEN 'HIGH'     THEN 2
                                WHEN 'MEDIUM'   THEN 3
                                ELSE 4
                            END
                """);
    }

    public List<Map<String, Object>> findingsByDomain() {
        return jdbc.queryForList("""
                SELECT  control_domain,
                        COUNT(*)                         AS finding_count,
                        COALESCE(SUM(exposure_amount),0) AS exposure
                FROM        audit_findings
                GROUP BY    control_domain
                ORDER BY    finding_count DESC
                """);
    }

    /**
     * Monthly spend with a running cumulative total, so the trend chart can show
     * both the monthly bar and the year-to-date line from one query.
     */
    public List<Map<String, Object>> monthlySpend() {
        return jdbc.queryForList("""
                WITH monthly AS (
                    SELECT  FORMATDATETIME(invoice_date, 'yyyy-MM') AS period,
                            COUNT(*)    AS txn_count,
                            SUM(amount) AS spend
                    FROM    transactions
                    GROUP BY FORMATDATETIME(invoice_date, 'yyyy-MM')
                )
                SELECT  m.period,
                        m.txn_count,
                        m.spend,
                        SUM(m.spend) OVER (ORDER BY m.period
                                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
                            AS cumulative_spend
                FROM     monthly m
                ORDER BY m.period
                """);
    }

    /**
     * Composite vendor risk league table. Spend concentration and finding
     * severity are combined into a single 0-100 score so reviewers can triage
     * the supplier base from one ranked list.
     */
    public List<Map<String, Object>> vendorRiskLeaderboard(int limit) {
        return jdbc.queryForList("""
                WITH vendor_findings AS (
                    SELECT  f.vendor_id,
                            COUNT(*)                                       AS finding_count,
                            SUM(CASE WHEN f.severity = 'CRITICAL' THEN 1 ELSE 0 END) AS critical_count,
                            SUM(CASE WHEN f.severity = 'HIGH'     THEN 1 ELSE 0 END) AS high_count,
                            COALESCE(SUM(f.exposure_amount), 0)            AS exposure,
                            MAX(f.risk_score)                              AS peak_risk_score
                    FROM    audit_findings f
                    WHERE   f.vendor_id IS NOT NULL
                    GROUP BY f.vendor_id
                ),
                spend AS (
                    SELECT  vendor_id,
                            COUNT(*)    AS txn_count,
                            SUM(amount) AS total_spend
                    FROM    transactions
                    GROUP BY vendor_id
                )
                SELECT  v.vendor_id,
                        v.vendor_code,
                        v.name      AS vendor_name,
                        v.category,
                        v.country,
                        COALESCE(s.txn_count, 0)      AS txn_count,
                        COALESCE(s.total_spend, 0)    AS total_spend,
                        COALESCE(vf.finding_count, 0) AS finding_count,
                        COALESCE(vf.critical_count,0) AS critical_count,
                        COALESCE(vf.high_count, 0)    AS high_count,
                        COALESCE(vf.exposure, 0)      AS exposure,
                        LEAST(100,
                              COALESCE(vf.critical_count,0) * 20
                            + COALESCE(vf.high_count,0)     * 10
                            + COALESCE(vf.finding_count,0)  * 3
                        ) AS risk_score,
                        RANK() OVER (ORDER BY COALESCE(s.total_spend,0) DESC) AS spend_rank
                FROM        vendors v
                JOIN        vendor_findings vf ON vf.vendor_id = v.vendor_id
                LEFT JOIN   spend s            ON s.vendor_id  = v.vendor_id
                ORDER BY    risk_score DESC, exposure DESC
                LIMIT ?
                """, limit);
    }

    /**
     * Observed leading-digit distribution across the whole ledger, returned with
     * the Benford expectation so the chart can plot both series.
     */
    public List<Map<String, Object>> benfordDistribution() {
        return jdbc.queryForList("""
                WITH digits AS (
                    SELECT CAST(LEFT(REPLACE(CAST(CAST(amount AS BIGINT) AS VARCHAR), '.', ''), 1)
                                AS INT) AS lead_digit
                    FROM   transactions
                    WHERE  amount >= 1
                )
                SELECT  lead_digit,
                        COUNT(*) AS observed_count,
                        ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) AS observed_pct
                FROM        digits
                WHERE       lead_digit BETWEEN 1 AND 9
                GROUP BY    lead_digit
                ORDER BY    lead_digit
                """);
    }

    /** Spend split by category, for the mix chart. */
    public List<Map<String, Object>> spendByCategory() {
        return jdbc.queryForList("""
                SELECT  v.category,
                        COUNT(t.txn_id) AS txn_count,
                        COALESCE(SUM(t.amount), 0) AS total_spend
                FROM        vendors v
                JOIN        transactions t ON t.vendor_id = v.vendor_id
                GROUP BY    v.category
                ORDER BY    total_spend DESC
                """);
    }

    /** Full transaction detail for one vendor, used by the drill-down panel. */
    public List<Map<String, Object>> transactionsForVendor(int vendorId, int limit) {
        return jdbc.queryForList("""
                SELECT  t.txn_ref, t.invoice_no, t.amount, t.invoice_date, t.posted_at,
                        t.cost_center, t.gl_account, t.payment_method, t.status,
                        ent.name AS entered_by_name,
                        apr.name AS approved_by_name
                FROM        transactions t
                JOIN        employees ent ON ent.employee_id = t.entered_by
                JOIN        employees apr ON apr.employee_id = t.approved_by
                WHERE       t.vendor_id = ?
                ORDER BY    t.invoice_date DESC
                LIMIT ?
                """, vendorId, limit);
    }
}
