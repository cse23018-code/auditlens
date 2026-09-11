package com.auditlens.service;

import com.auditlens.engine.AuditEngine;
import com.auditlens.repository.AnalyticsRepository;
import com.auditlens.repository.FindingRepository;
import com.auditlens.rules.AuditRule;
import com.auditlens.rules.BenfordDigitRule;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the complete dashboard payload from the analytics queries. Keeping
 * this in one place means the live API and the exported static snapshot are
 * guaranteed to render identical figures.
 */
@Service
public class DashboardService {

    private final AnalyticsRepository analytics;
    private final FindingRepository findings;
    private final AuditEngine engine;

    public DashboardService(AnalyticsRepository analytics, FindingRepository findings, AuditEngine engine) {
        this.analytics = analytics;
        this.findings = findings;
        this.engine = engine;
    }

    // -----------------------------------------------------------------
    // JDBC returns column labels in the database's own case (upper case on
    // H2 and Oracle, lower case on PostgreSQL). Normalising to camelCase here
    // keeps the published API contract identical whichever engine is behind it.
    // -----------------------------------------------------------------
    static String toCamelCase(String column) {
        String lower = column.toLowerCase();
        StringBuilder out = new StringBuilder(lower.length());
        boolean upperNext = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                out.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static Map<String, Object> normalise(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(toCamelCase(k), v));
        return out;
    }

    static List<Map<String, Object>> normalise(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(normalise(row));
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Dashboard payload
    // -----------------------------------------------------------------
    public Map<String, Object> fullSnapshot() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", java.time.LocalDateTime.now().toString());
        payload.put("engineVersion", AuditEngine.ENGINE_VERSION);
        payload.put("headline", normalise(analytics.headline()));
        payload.put("findingsByRule", normalise(analytics.findingsByRule()));
        payload.put("findingsBySeverity", normalise(analytics.findingsBySeverity()));
        payload.put("findingsByDomain", normalise(analytics.findingsByDomain()));
        payload.put("monthlySpend", normalise(analytics.monthlySpend()));
        payload.put("spendByCategory", normalise(analytics.spendByCategory()));
        payload.put("vendorRisk", normalise(analytics.vendorRiskLeaderboard(15)));
        payload.put("benford", benfordSeries());
        payload.put("catalogue", ruleCatalogue());
        payload.put("findings", normalise(findings.findFindings(null, null, 400)));
        payload.put("runs", normalise(findings.runHistory()));
        return payload;
    }

    public Map<String, Object> headline() {
        return normalise(analytics.headline());
    }

    public List<Map<String, Object>> findings(String severity, String ruleCode, Integer limit) {
        return normalise(findings.findFindings(severity, ruleCode, limit));
    }

    public List<Map<String, Object>> vendorRisk(int limit) {
        return normalise(analytics.vendorRiskLeaderboard(limit));
    }

    public List<Map<String, Object>> vendorTransactions(int vendorId, int limit) {
        return normalise(analytics.transactionsForVendor(vendorId, limit));
    }

    /** Observed leading-digit frequencies paired with the Benford expectation. */
    public List<Map<String, Object>> benfordSeries() {
        List<Map<String, Object>> observed = normalise(analytics.benfordDistribution());
        List<Map<String, Object>> series = new ArrayList<>();
        for (int digit = 1; digit <= 9; digit++) {
            final int d = digit;
            Map<String, Object> row = observed.stream()
                    .filter(r -> ((Number) r.get("leadDigit")).intValue() == d)
                    .findFirst()
                    .orElse(null);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("digit", digit);
            entry.put("observedCount", row == null ? 0 : ((Number) row.get("observedCount")).intValue());
            entry.put("observedPct", row == null ? 0.0 : ((Number) row.get("observedPct")).doubleValue());
            entry.put("expectedPct", BigDecimal.valueOf(100.0 * BenfordDigitRule.expectedFrequency(digit))
                    .setScale(2, RoundingMode.HALF_UP).doubleValue());
            series.add(entry);
        }
        return series;
    }

    /** The registered control catalogue, so the UI can document what was tested. */
    public List<Map<String, Object>> ruleCatalogue() {
        List<Map<String, Object>> catalogue = new ArrayList<>();
        for (AuditRule rule : engine.registeredRules()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", rule.code());
            entry.put("title", rule.title());
            entry.put("description", rule.description());
            entry.put("domain", rule.domain().label());
            entry.put("severity", rule.severity().name());
            catalogue.add(entry);
        }
        return catalogue;
    }
}
