package com.auditlens.model;

/**
 * Internal-control domain a rule belongs to. Mirrors how controls are grouped
 * in an IT General Controls / process-controls testing matrix.
 */
public enum ControlDomain {
    PAYMENT_INTEGRITY("Payment Integrity"),
    AUTHORISATION("Authorisation & Approval"),
    SEGREGATION_OF_DUTIES("Segregation of Duties"),
    MASTER_DATA("Vendor Master Data"),
    STATISTICAL_ANOMALY("Statistical Anomaly");

    private final String label;

    ControlDomain(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
