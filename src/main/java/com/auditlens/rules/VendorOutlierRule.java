package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * OUT-010 - Payment outside the vendor's own baseline.
 *
 * <p>Rather than testing every invoice against one global materiality figure,
 * this control builds a per-vendor baseline with windowed aggregates and reports
 * any payment more than three standard deviations above that vendor's own mean.
 * A 400,000 invoice is unremarkable for a hardware supplier and highly unusual
 * for one that has only ever billed 20,000 a month.</p>
 */
@Component
public class VendorOutlierRule extends AbstractAuditRule {

    private static final double SIGMA_THRESHOLD = 3.0;
    private static final int MIN_SAMPLE = 12;

    private static final String SQL = """
            WITH baseline AS (
                SELECT  t.txn_id,
                        t.txn_ref,
                        t.vendor_id,
                        t.amount,
                        t.invoice_date,
                        AVG(t.amount)        OVER (PARTITION BY t.vendor_id) AS vendor_mean,
                        STDDEV_POP(t.amount) OVER (PARTITION BY t.vendor_id) AS vendor_sd,
                        COUNT(*)             OVER (PARTITION BY t.vendor_id) AS vendor_n
                FROM    transactions t
                WHERE   t.status <> 'REVERSED'
            )
            SELECT  b.txn_id,
                    b.txn_ref,
                    b.vendor_id,
                    b.amount,
                    b.invoice_date,
                    b.vendor_mean,
                    b.vendor_sd,
                    b.vendor_n,
                    v.name AS vendor_name,
                    (b.amount - b.vendor_mean) / b.vendor_sd AS z_score
            FROM        baseline b
            JOIN        vendors v ON v.vendor_id = b.vendor_id
            WHERE       b.vendor_n >= ?
                    AND b.vendor_sd > 0
                    AND (b.amount - b.vendor_mean) / b.vendor_sd > ?
            ORDER BY    z_score DESC
            """;

    @Override public String code()          { return "OUT-010"; }
    @Override public String title()         { return "Vendor Baseline Outlier"; }
    @Override public String description()   { return "Payment more than three standard deviations above the vendor's own historical mean."; }
    @Override public ControlDomain domain() { return ControlDomain.STATISTICAL_ANOMALY; }
    @Override public Severity severity()    { return Severity.MEDIUM; }
    @Override public int order()            { return 100; }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        List<AuditFinding> findings = new ArrayList<>();
        ctx.jdbc().query(SQL, rs -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            BigDecimal mean = rs.getBigDecimal("vendor_mean");
            double z = rs.getDouble("z_score");
            String summary = "%s billed %s, %.1f standard deviations above its own average".formatted(
                    rs.getString("vendor_name"), DuplicatePaymentRule.money(amount), z);
            String evidence = "%s posted %s against a vendor mean of %s across %d invoices (z = %.2f, threshold %.1f)."
                    .formatted(rs.getString("txn_ref"), DuplicatePaymentRule.money(amount),
                               DuplicatePaymentRule.money(mean), rs.getInt("vendor_n"),
                               z, SIGMA_THRESHOLD);
            findings.add(finding(rs.getInt("vendor_id"), rs.getInt("txn_id"),
                    amount.subtract(mean), scoreFor(z, SIGMA_THRESHOLD), summary, evidence));
        }, MIN_SAMPLE, SIGMA_THRESHOLD);
        return findings;
    }
}
