package com.auditlens.repository;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persists engine output and serves it back to the API. Findings are written in
 * a single JDBC batch so a run that raises several hundred exceptions still
 * completes in one round trip.
 */
@Repository
public class FindingRepository {

    private final JdbcTemplate jdbc;
    private final AtomicInteger runSequence = new AtomicInteger(0);
    private final AtomicInteger findingSequence = new AtomicInteger(0);

    public FindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int openRun(String engineVersion) {
        int runId = runSequence.incrementAndGet();
        jdbc.update("""
                INSERT INTO audit_runs (run_id, started_at, engine_version)
                VALUES (?, ?, ?)
                """, runId, Timestamp.valueOf(LocalDateTime.now()), engineVersion);
        return runId;
    }

    public void closeRun(int runId, int txnsScanned, int findingsCount, long durationMs) {
        jdbc.update("""
                UPDATE audit_runs
                   SET completed_at   = ?,
                       txns_scanned   = ?,
                       findings_count = ?,
                       duration_ms    = ?
                 WHERE run_id = ?
                """, Timestamp.valueOf(LocalDateTime.now()), txnsScanned, findingsCount,
                durationMs, runId);
    }

    /** Clears prior output so each run reports against a clean slate. */
    public void clearFindings() {
        jdbc.update("DELETE FROM audit_findings");
        findingSequence.set(0);
    }

    public void saveAll(int runId, List<AuditFinding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        for (AuditFinding f : findings) {
            f.setRunId(runId);
            f.setFindingId(findingSequence.incrementAndGet());
        }
        jdbc.batchUpdate("""
                INSERT INTO audit_findings
                    (finding_id, run_id, rule_code, rule_title, control_domain, severity,
                     risk_score, vendor_id, txn_id, exposure_amount, detected_at, summary, evidence)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                AuditFinding f = findings.get(i);
                ps.setInt(1, f.getFindingId());
                ps.setInt(2, f.getRunId());
                ps.setString(3, f.getRuleCode());
                ps.setString(4, f.getRuleTitle());
                ps.setString(5, f.getControlDomain().label());
                ps.setString(6, f.getSeverity().name());
                ps.setInt(7, f.getRiskScore());
                if (f.getVendorId() == null) {
                    ps.setNull(8, Types.INTEGER);
                } else {
                    ps.setInt(8, f.getVendorId());
                }
                if (f.getTxnId() == null) {
                    ps.setNull(9, Types.INTEGER);
                } else {
                    ps.setInt(9, f.getTxnId());
                }
                ps.setBigDecimal(10, f.getExposureAmount());
                ps.setTimestamp(11, Timestamp.valueOf(f.getDetectedAt()));
                ps.setString(12, truncate(f.getSummary(), 300));
                ps.setString(13, truncate(f.getEvidence(), 1000));
            }

            @Override
            public int getBatchSize() {
                return findings.size();
            }
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** Findings joined to vendor and transaction context, optionally filtered. */
    public List<Map<String, Object>> findFindings(String severity, String ruleCode, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT  f.finding_id,
                        f.rule_code,
                        f.rule_title,
                        f.control_domain,
                        f.severity,
                        f.risk_score,
                        f.exposure_amount,
                        f.summary,
                        f.evidence,
                        f.detected_at,
                        v.vendor_code,
                        v.name        AS vendor_name,
                        v.category    AS vendor_category,
                        t.txn_ref,
                        t.invoice_no,
                        t.amount      AS txn_amount,
                        t.invoice_date
                FROM        audit_findings f
                LEFT JOIN   vendors      v ON v.vendor_id = f.vendor_id
                LEFT JOIN   transactions t ON t.txn_id    = f.txn_id
                WHERE       1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND f.severity = ? ");
            args.add(severity.toUpperCase());
        }
        if (ruleCode != null && !ruleCode.isBlank()) {
            sql.append(" AND f.rule_code = ? ");
            args.add(ruleCode.toUpperCase());
        }
        sql.append("""
                 ORDER BY CASE f.severity
                              WHEN 'CRITICAL' THEN 1
                              WHEN 'HIGH'     THEN 2
                              WHEN 'MEDIUM'   THEN 3
                              ELSE 4
                          END,
                          f.risk_score DESC,
                          f.exposure_amount DESC
                """);
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ? ");
            args.add(limit);
        }
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> runHistory() {
        return jdbc.queryForList("""
                SELECT run_id, started_at, completed_at, txns_scanned,
                       findings_count, duration_ms, engine_version
                  FROM audit_runs
                 ORDER BY run_id DESC
                """);
    }

    /** Convenience lookup used by tests and the severity filter UI. */
    public int countBySeverity(Severity severity) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_findings WHERE severity = ?",
                Integer.class, severity.name());
        return n == null ? 0 : n;
    }

    public int countByDomain(ControlDomain domain) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_findings WHERE control_domain = ?",
                Integer.class, domain.label());
        return n == null ? 0 : n;
    }
}
