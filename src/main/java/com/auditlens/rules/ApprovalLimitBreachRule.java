package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * LMT-004 - Approval authority exceeded.
 *
 * <p>Joins each payment to the delegated authority of the user who approved it
 * and reports every release above that limit. Exposure is measured as the amount
 * released beyond the mandate, not the whole invoice value.</p>
 */
@Component
public class ApprovalLimitBreachRule extends AbstractAuditRule {

    private static final String SQL = """
            SELECT  t.txn_id,
                    t.txn_ref,
                    t.vendor_id,
                    t.amount,
                    v.name AS vendor_name,
                    e.name AS approver_name,
                    e.employee_code,
                    e.job_role,
                    e.approval_limit,
                    (t.amount - e.approval_limit) AS excess
            FROM        transactions t
            JOIN        employees e ON e.employee_id = t.approved_by
            JOIN        vendors   v ON v.vendor_id   = t.vendor_id
            WHERE       t.amount > e.approval_limit
            ORDER BY    excess DESC
            """;

    @Override public String code()          { return "LMT-004"; }
    @Override public String title()         { return "Approval Authority Exceeded"; }
    @Override public String description()   { return "Payment released by an approver whose delegated limit is below the invoice value."; }
    @Override public ControlDomain domain() { return ControlDomain.AUTHORISATION; }
    @Override public Severity severity()    { return Severity.HIGH; }
    @Override public int order()            { return 40; }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        List<AuditFinding> findings = new ArrayList<>();
        ctx.jdbc().query(SQL, rs -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            BigDecimal limit  = rs.getBigDecimal("approval_limit");
            BigDecimal excess = rs.getBigDecimal("excess");
            String summary = "%s approved %s against a %s delegated limit".formatted(
                    rs.getString("approver_name"), DuplicatePaymentRule.money(amount),
                    DuplicatePaymentRule.money(limit));
            String evidence = "%s released to %s by %s [%s, %s] - %s above delegated authority."
                    .formatted(rs.getString("txn_ref"), rs.getString("vendor_name"),
                               rs.getString("approver_name"), rs.getString("employee_code"),
                               rs.getString("job_role"), DuplicatePaymentRule.money(excess));
            findings.add(finding(rs.getInt("vendor_id"), rs.getInt("txn_id"), excess,
                    scoreFor(amount.doubleValue(), limit.doubleValue()), summary, evidence));
        });
        return findings;
    }
}
