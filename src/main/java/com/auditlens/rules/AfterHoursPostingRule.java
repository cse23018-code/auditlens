package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import com.auditlens.model.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AHR-005 - Out-of-hours posting.
 *
 * <p>Material payments posted at night or over a weekend fall outside the window
 * in which supervisory review actually happens, so they are tested separately.
 * Evaluated in Java over the pre-loaded population to keep the control free of
 * database-specific calendar functions.</p>
 */
@Component
public class AfterHoursPostingRule extends AbstractAuditRule {

    private static final int BUSINESS_START = 8;
    private static final int BUSINESS_END   = 20;
    private static final BigDecimal MATERIALITY = new BigDecimal("50000");

    @Override public String code()          { return "AHR-005"; }
    @Override public String title()         { return "Out-of-Hours Posting"; }
    @Override public String description()   { return "Material payment posted outside 08:00-20:00 on a working day."; }
    @Override public ControlDomain domain() { return ControlDomain.PAYMENT_INTEGRITY; }
    @Override public Severity severity()    { return Severity.MEDIUM; }
    @Override public int order()            { return 50; }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        List<AuditFinding> findings = new ArrayList<>();
        for (Transaction t : ctx.transactions()) {
            if (t.amount().compareTo(MATERIALITY) < 0) {
                continue;
            }
            LocalDateTime posted = t.postedAt();
            DayOfWeek day = posted.getDayOfWeek();
            boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
            int hour = posted.getHour();
            boolean afterHours = hour < BUSINESS_START || hour >= BUSINESS_END;
            if (!weekend && !afterHours) {
                continue;
            }
            String reason = weekend && afterHours ? "weekend, outside working hours"
                          : weekend ? "weekend"
                          : "outside working hours";
            String summary = "%s posted %s at %02d:%02d".formatted(
                    t.txnRef(), reason, hour, posted.getMinute());
            String evidence = "%s to %s was posted on %s at %02d:%02d, outside the %02d:00-%02d:00 review window."
                    .formatted(DuplicatePaymentRule.money(t.amount()), ctx.vendorName(t.vendorId()),
                               posted.toLocalDate(), hour, posted.getMinute(),
                               BUSINESS_START, BUSINESS_END);
            int score = severity().baseScore() + (weekend && afterHours ? 10 : 0);
            findings.add(finding(t.vendorId(), t.txnId(), t.amount(), score, summary, evidence));
        }
        return findings;
    }
}
