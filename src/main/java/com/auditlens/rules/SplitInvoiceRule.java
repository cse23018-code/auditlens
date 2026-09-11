package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * SPL-002 - Split invoice / approval-threshold circumvention.
 *
 * <p>Flags vendors who submit three or more invoices inside a 5-day window where
 * every individual invoice sits below the approval threshold but the combined
 * total clears it. Slicing a purchase to stay under a sign-off limit is a
 * textbook procurement control breach.</p>
 */
@Component
public class SplitInvoiceRule extends AbstractAuditRule {

    private static final BigDecimal THRESHOLD = new BigDecimal("100000");

    private static final String SQL = """
            WITH sub_threshold AS (
                SELECT  t.vendor_id,
                        t.invoice_date,
                        COUNT(*)      AS slice_count,
                        SUM(t.amount) AS day_total,
                        MAX(t.amount) AS largest_slice,
                        MIN(t.txn_id) AS anchor_txn
                FROM    transactions t
                WHERE   t.amount < ?
                  AND   t.status <> 'REVERSED'
                GROUP BY t.vendor_id, t.invoice_date
            ),
            clustered AS (
                SELECT  w.vendor_id,
                        w.invoice_date                AS window_start,
                        SUM(n.slice_count)            AS slice_count,
                        SUM(n.day_total)              AS window_total,
                        MAX(n.largest_slice)          AS largest_slice,
                        MIN(n.anchor_txn)             AS anchor_txn
                FROM    sub_threshold w
                JOIN    sub_threshold n
                     ON n.vendor_id = w.vendor_id
                    AND n.invoice_date >= w.invoice_date
                    AND n.invoice_date <= DATEADD('DAY', 5, w.invoice_date)
                GROUP BY w.vendor_id, w.invoice_date
            )
            SELECT  c.vendor_id,
                    v.name AS vendor_name,
                    c.window_start,
                    c.slice_count,
                    c.window_total,
                    c.largest_slice,
                    c.anchor_txn
            FROM        clustered c
            JOIN        vendors v ON v.vendor_id = c.vendor_id
            WHERE       c.slice_count  >= 3
                    AND c.window_total > ?
            ORDER BY    c.window_total DESC
            """;

    @Override public String code()          { return "SPL-002"; }
    @Override public String title()         { return "Split Invoice / Threshold Circumvention"; }
    @Override public String description()   { return "Three or more sub-threshold invoices in a 5-day window that together exceed the approval limit."; }
    @Override public ControlDomain domain() { return ControlDomain.AUTHORISATION; }
    @Override public Severity severity()    { return Severity.HIGH; }
    @Override public int order()            { return 20; }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        List<AuditFinding> findings = new ArrayList<>();
        ctx.jdbc().query(SQL, rs -> {
            BigDecimal total = rs.getBigDecimal("window_total");
            String summary = "%d sub-threshold invoices from %s total %s within 5 days".formatted(
                    rs.getInt("slice_count"), rs.getString("vendor_name"),
                    DuplicatePaymentRule.money(total));
            String evidence = "Largest single slice %s stays under the %s approval threshold, but the cluster starting %s totals %s across %d invoices."
                    .formatted(DuplicatePaymentRule.money(rs.getBigDecimal("largest_slice")),
                               DuplicatePaymentRule.money(THRESHOLD),
                               rs.getDate("window_start").toLocalDate(),
                               DuplicatePaymentRule.money(total),
                               rs.getInt("slice_count"));
            findings.add(finding(rs.getInt("vendor_id"), rs.getInt("anchor_txn"), total,
                    scoreFor(total.doubleValue(), THRESHOLD.doubleValue()), summary, evidence));
        }, THRESHOLD, THRESHOLD);
        return findings;
    }
}
