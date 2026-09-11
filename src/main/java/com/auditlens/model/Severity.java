package com.auditlens.model;

/**
 * Audit finding severity, ordered from most to least serious.
 * Each level carries a base risk weight used by the composite vendor risk score.
 */
public enum Severity {
    CRITICAL(90),
    HIGH(70),
    MEDIUM(45),
    LOW(20);

    private final int baseScore;

    Severity(int baseScore) {
        this.baseScore = baseScore;
    }

    public int baseScore() {
        return baseScore;
    }
}
