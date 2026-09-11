package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * RND-006 - Round-amount anomaly.
 *
 * <p>Genuine invoices carry tax, freight and line-level rounding, so they rarely
 * land on an exact multiple of 10,000. Large perfectly round values are a common
 * signature of estimated accruals posted as invoices, or of fabricated billing.</p>
 */
@Component
public class RoundAmountRule extends AbstractAuditRule {

    private static final BigDecimal MATERIALITY = new BigDecimal("75000");

    private static final String SQL = """
            SELECT  t.txn_id,
                    t.txn_ref,
                    t.vendor_id,
                    t.amount,
                    t.invoice_no,
                    t.gl_account,
                    v.name AS vendor_name
            FROM        transactions t
            JOIN        vendors v ON v.vendor_id = t.vendor_id
            WHERE       t.amount >= ?
                    AND MOD(CAST(t.amount AS BIGINT), 10000) = 0
                    AND t.amount = CAST(t.amount AS BIGINT)
            ORDER BY    t.amount DESC
            """;

    @Override public String code()          { return "RND-006"; }
    @Override public String title()         { return "Round-Amount Anomaly"; }
    @Override public String description()   { return "Material invoice booked at an exact multiple of 10,000 with no tax or rounding residue."; }
    @Override public ControlDomain domain() { return ControlDomain.STATISTICAL_ANOMALY; }
    @Override public Severity severity()    { return Severity.MEDIUM; }
    @Override public int order()            { return 60; }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        List<AuditFinding> findings = new ArrayList<>();
        ctx.jdbc().query(SQL, rs -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            String summary = "%s billed an exactly round %s".formatted(
                    rs.getString("vendor_name"), DuplicatePaymentRule.money(amount));
            String evidence = "%s (invoice %s, GL %s) is an exact multiple of 10,000 with no tax or rounding residue."
                    .formatted(rs.getString("txn_ref"), rs.getString("invoice_no"),
                               rs.getString("gl_account"));
            findings.add(finding(rs.getInt("vendor_id"), rs.getInt("txn_id"), amount,
                    scoreFor(amount.doubleValue(), MATERIALITY.doubleValue()), summary, evidence));
        }, MATERIALITY);
        return findings;
    }
}
