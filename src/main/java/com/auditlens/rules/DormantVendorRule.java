package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * DRM-009 - Dormant vendor reactivation.
 *
 * <p>Uses a {@code LAG} window function to measure the gap between each payment
 * and the previous one for the same vendor. A supplier that has been silent for
 * more than 180 days and then receives a material payment is a standard shell-
 * vendor indicator: dormant accounts on the master file are attractive because
 * nobody is monitoring them.</p>
 */
@Component
public class DormantVendorRule extends AbstractAuditRule {

    private static final int DORMANCY_DAYS = 180;
    private static final BigDecimal MATERIALITY = new BigDecimal("60000");

    private static final String SQL = """
            WITH sequenced AS (
                SELECT  t.txn_id,
                        t.txn_ref,
                        t.vendor_id,
                        t.amount,
                        t.invoice_date,
                        LAG(t.invoice_date) OVER (
                            PARTITION BY t.vendor_id
                            ORDER BY     t.invoice_date, t.txn_id
                        ) AS prior_invoice_date
                FROM    transactions t
                WHERE   t.status <> 'REVERSED'
            )
            SELECT  s.txn_id,
                    s.txn_ref,
                    s.vendor_id,
                    s.amount,
                    s.invoice_date,
                    s.prior_invoice_date,
                    v.name   AS vendor_name,
                    v.status AS vendor_status,
                    DATEDIFF('DAY', s.prior_invoice_date, s.invoice_date) AS gap_days
            FROM        sequenced s
            JOIN        vendors v ON v.vendor_id = s.vendor_id
            WHERE       s.prior_invoice_date IS NOT NULL
                    AND DATEDIFF('DAY', s.prior_invoice_date, s.invoice_date) > ?
                    AND s.amount >= ?
            ORDER BY    s.amount DESC
            """;

    @Override public String code()          { return "DRM-009"; }
    @Override public String title()         { return "Dormant Vendor Reactivation"; }
    @Override public String description()   { return "Material payment to a vendor that had been inactive for more than 180 days."; }
    @Override public ControlDomain domain() { return ControlDomain.MASTER_DATA; }
    @Override public Severity severity()    { return Severity.MEDIUM; }
    @Override public int order()            { return 90; }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        List<AuditFinding> findings = new ArrayList<>();
        ctx.jdbc().query(SQL, rs -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            int gapDays = rs.getInt("gap_days");
            String summary = "%s received %s after %d days of inactivity".formatted(
                    rs.getString("vendor_name"), DuplicatePaymentRule.money(amount), gapDays);
            String evidence = "Previous invoice dated %s; %s for %s posted %s, a gap of %d days against a %d-day dormancy threshold."
                    .formatted(rs.getDate("prior_invoice_date").toLocalDate(),
                               rs.getString("txn_ref"), DuplicatePaymentRule.money(amount),
                               rs.getDate("invoice_date").toLocalDate(), gapDays, DORMANCY_DAYS);
            findings.add(finding(rs.getInt("vendor_id"), rs.getInt("txn_id"), amount,
                    scoreFor(gapDays, DORMANCY_DAYS), summary, evidence));
        }, DORMANCY_DAYS, MATERIALITY);
        return findings;
    }
}
