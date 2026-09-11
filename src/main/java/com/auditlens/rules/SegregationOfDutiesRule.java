package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * SOD-003 - Segregation of duties breach.
 *
 * <p>A payment must never be entered and approved by the same user. This is the
 * control every GRC toolset tests first: a single actor holding both rights can
 * originate and release a payment unchallenged.</p>
 */
@Component
public class SegregationOfDutiesRule extends AbstractAuditRule {

    private static final String SQL = """
            SELECT  t.txn_id,
                    t.txn_ref,
                    t.vendor_id,
                    t.amount,
                    t.invoice_date,
                    v.name AS vendor_name,
                    e.name AS actor_name,
                    e.employee_code,
                    e.department,
                    e.job_role
            FROM        transactions t
            JOIN        vendors   v ON v.vendor_id   = t.vendor_id
            JOIN        employees e ON e.employee_id = t.entered_by
            WHERE       t.entered_by = t.approved_by
            ORDER BY    t.amount DESC
            """;

    @Override public String code()          { return "SOD-003"; }
    @Override public String title()         { return "Segregation of Duties Breach"; }
    @Override public String description()   { return "The same user both entered and approved the payment."; }
    @Override public ControlDomain domain() { return ControlDomain.SEGREGATION_OF_DUTIES; }
    @Override public Severity severity()    { return Severity.CRITICAL; }
    @Override public int order()            { return 30; }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        List<AuditFinding> findings = new ArrayList<>();
        ctx.jdbc().query(SQL, rs -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            String summary = "%s was entered and approved by the same user (%s)".formatted(
                    rs.getString("txn_ref"), rs.getString("actor_name"));
            String evidence = "%s [%s, %s in %s] raised and self-approved %s to %s on %s."
                    .formatted(rs.getString("actor_name"), rs.getString("employee_code"),
                               rs.getString("job_role"), rs.getString("department"),
                               DuplicatePaymentRule.money(amount), rs.getString("vendor_name"),
                               rs.getDate("invoice_date").toLocalDate());
            findings.add(finding(rs.getInt("vendor_id"), rs.getInt("txn_id"), amount,
                    severity().baseScore(), summary, evidence));
        });
        return findings;
    }
}
