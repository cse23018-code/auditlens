package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * BNK-008 - Payment released shortly after a bank-detail change.
 *
 * <p>The classic supplier-mandate fraud: an attacker changes a vendor's bank
 * account on the master file, then waits for the next payment run to divert
 * funds. Any material payment within 14 days of a bank change is tested, and the
 * control escalates when the same user made the change and keyed the payment.</p>
 */
@Component
public class PostBankChangePaymentRule extends AbstractAuditRule {

    private static final int WINDOW_DAYS = 14;
    private static final BigDecimal MATERIALITY = new BigDecimal("40000");

    private static final String SQL = """
            SELECT  t.txn_id,
                    t.txn_ref,
                    t.vendor_id,
                    t.amount,
                    t.invoice_date,
                    t.entered_by,
                    v.name       AS vendor_name,
                    c.changed_on,
                    c.old_account,
                    c.new_account,
                    c.changed_by,
                    ch.name      AS changed_by_name,
                    DATEDIFF('DAY', c.changed_on, t.invoice_date) AS days_after
            FROM        transactions t
            JOIN        vendor_bank_changes c ON c.vendor_id = t.vendor_id
            JOIN        vendors   v  ON v.vendor_id   = t.vendor_id
            JOIN        employees ch ON ch.employee_id = c.changed_by
            WHERE       t.invoice_date >= c.changed_on
                    AND DATEDIFF('DAY', c.changed_on, t.invoice_date) <= ?
                    AND t.amount >= ?
            ORDER BY    t.amount DESC
            """;

    @Override public String code()          { return "BNK-008"; }
    @Override public String title()         { return "Payment After Bank-Detail Change"; }
    @Override public String description()   { return "Material payment released within 14 days of a change to the vendor bank account."; }
    @Override public ControlDomain domain() { return ControlDomain.MASTER_DATA; }
    @Override public Severity severity()    { return Severity.CRITICAL; }
    @Override public int order()            { return 80; }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        List<AuditFinding> findings = new ArrayList<>();
        ctx.jdbc().query(SQL, rs -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            int daysAfter = rs.getInt("days_after");
            boolean sameActor = rs.getInt("changed_by") == rs.getInt("entered_by");

            String summary = "%s paid %s just %d day(s) after its bank account was changed".formatted(
                    rs.getString("vendor_name"), DuplicatePaymentRule.money(amount), daysAfter);
            String evidence = ("Account moved from %s to %s on %s by %s. %s for %s followed on %s."
                    + (sameActor ? " The same user both changed the account and keyed the payment." : ""))
                    .formatted(rs.getString("old_account"), rs.getString("new_account"),
                               rs.getDate("changed_on").toLocalDate(), rs.getString("changed_by_name"),
                               rs.getString("txn_ref"), DuplicatePaymentRule.money(amount),
                               rs.getDate("invoice_date").toLocalDate());

            int score = severity().baseScore() + (sameActor ? 10 : 0) + (daysAfter <= 3 ? 5 : 0);
            findings.add(finding(rs.getInt("vendor_id"), rs.getInt("txn_id"), amount,
                    score, summary, evidence));
        }, WINDOW_DAYS, MATERIALITY);
        return findings;
    }
}
