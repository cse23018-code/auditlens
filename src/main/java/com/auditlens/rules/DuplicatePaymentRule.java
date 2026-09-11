package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * DUP-001 - Duplicate payment detection.
 *
 * <p>Pairs invoices posted to the same vendor for an identical amount within a
 * 10-day window. This catches the most common accounts-payable leakage: the
 * same invoice keyed twice under different references.</p>
 */
@Component
public class DuplicatePaymentRule extends AbstractAuditRule {

    private static final int WINDOW_DAYS = 10;

    private static final String SQL = """
            SELECT  a.txn_id     AS txn_a,
                    a.txn_ref    AS ref_a,
                    b.txn_id     AS txn_b,
                    b.txn_ref    AS ref_b,
                    a.vendor_id  AS vendor_id,
                    v.name       AS vendor_name,
                    a.amount     AS amount,
                    a.invoice_no AS inv_a,
                    b.invoice_no AS inv_b,
                    ABS(DATEDIFF('DAY', a.invoice_date, b.invoice_date)) AS day_gap
            FROM        transactions a
            JOIN        transactions b
                     ON a.vendor_id = b.vendor_id
                    AND a.amount    = b.amount
                    AND a.txn_id    < b.txn_id
                    AND ABS(DATEDIFF('DAY', a.invoice_date, b.invoice_date)) <= ?
            JOIN        vendors v ON v.vendor_id = a.vendor_id
            WHERE       a.status <> 'REVERSED'
                    AND b.status <> 'REVERSED'
            ORDER BY    a.amount DESC
            """;

    @Override public String code()          { return "DUP-001"; }
    @Override public String title()         { return "Duplicate Payment Detection"; }
    @Override public String description()   { return "Identical amount billed twice by the same vendor within " + WINDOW_DAYS + " days."; }
    @Override public ControlDomain domain() { return ControlDomain.PAYMENT_INTEGRITY; }
    @Override public Severity severity()    { return Severity.CRITICAL; }
    @Override public int order()            { return 10; }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        List<AuditFinding> findings = new ArrayList<>();
        ctx.jdbc().query(SQL, rs -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            int dayGap = rs.getInt("day_gap");
            String summary = "Possible duplicate payment of %s to %s".formatted(
                    money(amount), rs.getString("vendor_name"));
            String evidence = "%s (invoice %s) and %s (invoice %s) carry the same amount %d days apart."
                    .formatted(rs.getString("ref_a"), rs.getString("inv_a"),
                               rs.getString("ref_b"), rs.getString("inv_b"), dayGap);
            findings.add(finding(rs.getInt("vendor_id"), rs.getInt("txn_b"), amount,
                    severity().baseScore() + (dayGap <= 2 ? 8 : 0), summary, evidence));
        }, WINDOW_DAYS);
        return findings;
    }

    /** Shared money formatter used across rule evidence strings. */
    static String money(BigDecimal v) {
        return "INR " + String.format("%,.2f", v);
    }
}
