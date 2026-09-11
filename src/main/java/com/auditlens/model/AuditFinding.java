package com.auditlens.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single exception raised by an audit rule against the transaction population.
 * Findings are persisted per run so that results are reproducible and auditable.
 */
public class AuditFinding {

    private int findingId;
    private int runId;
    private final String ruleCode;
    private final String ruleTitle;
    private final ControlDomain controlDomain;
    private final Severity severity;
    private final int riskScore;
    private final Integer vendorId;
    private final Integer txnId;
    private final BigDecimal exposureAmount;
    private final String summary;
    private final String evidence;
    private LocalDateTime detectedAt = LocalDateTime.now();

    public AuditFinding(String ruleCode, String ruleTitle, ControlDomain controlDomain,
                        Severity severity, int riskScore, Integer vendorId, Integer txnId,
                        BigDecimal exposureAmount, String summary, String evidence) {
        this.ruleCode = ruleCode;
        this.ruleTitle = ruleTitle;
        this.controlDomain = controlDomain;
        this.severity = severity;
        this.riskScore = Math.max(0, Math.min(100, riskScore));
        this.vendorId = vendorId;
        this.txnId = txnId;
        this.exposureAmount = exposureAmount == null ? BigDecimal.ZERO : exposureAmount;
        this.summary = summary;
        this.evidence = evidence;
    }

    public int getFindingId()             { return findingId; }
    public void setFindingId(int id)      { this.findingId = id; }
    public int getRunId()                 { return runId; }
    public void setRunId(int runId)       { this.runId = runId; }
    public String getRuleCode()           { return ruleCode; }
    public String getRuleTitle()          { return ruleTitle; }
    public ControlDomain getControlDomain() { return controlDomain; }
    public Severity getSeverity()         { return severity; }
    public int getRiskScore()             { return riskScore; }
    public Integer getVendorId()          { return vendorId; }
    public Integer getTxnId()             { return txnId; }
    public BigDecimal getExposureAmount() { return exposureAmount; }
    public String getSummary()            { return summary; }
    public String getEvidence()           { return evidence; }
    public LocalDateTime getDetectedAt()  { return detectedAt; }
    public void setDetectedAt(LocalDateTime t) { this.detectedAt = t; }
}
