package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import java.math.BigDecimal;

/**
 * Convenience base class that builds findings already stamped with this
 * rule's code, title, domain and severity, so concrete rules only supply
 * the evidence.
 */
public abstract class AbstractAuditRule implements AuditRule {

    protected AuditFinding finding(Integer vendorId, Integer txnId, BigDecimal exposure,
                                   int riskScore, String summary, String evidence) {
        return new AuditFinding(code(), title(), domain(), severity(),
                riskScore, vendorId, txnId, exposure, summary, evidence);
    }

    /** Scales the severity base score by how far a metric exceeds its threshold. */
    protected int scoreFor(double observed, double threshold) {
        if (threshold <= 0) {
            return severity().baseScore();
        }
        double ratio = observed / threshold;
        int uplift = (int) Math.round(Math.min(10.0, (ratio - 1.0) * 10.0));
        return Math.max(0, Math.min(100, severity().baseScore() + uplift));
    }

    @Override
    public String toString() {
        return code() + " " + title();
    }
}
