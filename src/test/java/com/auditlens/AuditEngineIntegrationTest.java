package com.auditlens;

import com.auditlens.engine.AuditEngine;
import com.auditlens.repository.FindingRepository;
import com.auditlens.rules.AuditRule;
import com.auditlens.service.DashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the engine against the seeded ledger. The seeder plants a
 * known set of control breaches, so every registered control must return at
 * least one finding; a control that silently stops matching will fail here.
 */
@SpringBootTest
class AuditEngineIntegrationTest {

    @Autowired AuditEngine engine;
    @Autowired FindingRepository findings;
    @Autowired DashboardService dashboard;

    @Test
    @DisplayName("All ten controls are discovered and registered")
    void allControlsRegistered() {
        List<AuditRule> rules = engine.registeredRules();
        assertEquals(10, rules.size(), "expected ten registered controls");

        Set<String> codes = rules.stream().map(AuditRule::code).collect(Collectors.toSet());
        assertTrue(codes.containsAll(Set.of(
                "DUP-001", "SPL-002", "SOD-003", "LMT-004", "AHR-005",
                "RND-006", "BNF-007", "BNK-008", "DRM-009", "OUT-010")));

        // Rule codes must be unique, otherwise reporting collapses two controls.
        assertEquals(rules.size(), codes.size(), "rule codes must be unique");
    }

    @Test
    @DisplayName("A run detects the planted breaches on every control")
    void everyControlRaisesFindings() {
        AuditEngine.AuditRunResult result = engine.run();

        assertTrue(result.transactionsScanned() > 5_000,
                "expected the full seeded ledger to be scanned");
        assertTrue(result.findingsRaised() > 0, "engine returned no findings");

        result.findingsByRule().forEach((code, n) ->
                assertTrue(n != null && n > 0,
                        "control " + code + " raised no findings against the planted breaches"));
    }

    @Test
    @DisplayName("Findings are persisted and readable through the query layer")
    void findingsArePersisted() {
        engine.run();

        List<Map<String, Object>> critical = dashboard.findings("CRITICAL", null, 50);
        assertFalse(critical.isEmpty(), "expected critical findings");
        critical.forEach(f -> assertEquals("CRITICAL", f.get("severity")));

        List<Map<String, Object>> duplicates = dashboard.findings(null, "DUP-001", 50);
        assertFalse(duplicates.isEmpty(), "expected duplicate-payment findings");
        duplicates.forEach(f -> assertEquals("DUP-001", f.get("ruleCode")));
    }

    @Test
    @DisplayName("Re-running the engine replaces rather than accumulates findings")
    void runsAreIdempotent() {
        AuditEngine.AuditRunResult first = engine.run();
        AuditEngine.AuditRunResult second = engine.run();

        assertEquals(first.findingsRaised(), second.findingsRaised(),
                "a deterministic ledger must produce a stable finding count");

        int stored = dashboard.findings(null, null, 5000).size();
        assertEquals(second.findingsRaised(), stored,
                "stored findings should match the latest run only");
    }

    @Test
    @DisplayName("Dashboard payload exposes every section the UI needs")
    void dashboardPayloadIsComplete() {
        engine.run();
        Map<String, Object> snapshot = dashboard.fullSnapshot();

        assertTrue(snapshot.keySet().containsAll(Set.of(
                "headline", "findingsByRule", "findingsBySeverity", "monthlySpend",
                "vendorRisk", "benford", "catalogue", "findings")));

        @SuppressWarnings("unchecked")
        Map<String, Object> headline = (Map<String, Object>) snapshot.get("headline");
        assertTrue(((Number) headline.get("txnCount")).intValue() > 0);
        assertTrue(((Number) headline.get("findingCount")).intValue() > 0);

        // Keys must be camelCase regardless of the database's column casing.
        assertTrue(headline.containsKey("totalSpend"),
                "expected normalised camelCase keys, got " + headline.keySet());
    }

    @Test
    @DisplayName("Benford series covers all nine digits with both observed and expected values")
    void benfordSeriesIsComplete() {
        List<Map<String, Object>> series = dashboard.benfordSeries();
        assertEquals(9, series.size());

        double totalExpected = 0.0;
        for (Map<String, Object> row : series) {
            assertNotNull(row.get("observedPct"));
            totalExpected += ((Number) row.get("expectedPct")).doubleValue();
        }
        assertEquals(100.0, totalExpected, 0.5, "Benford expectation should total 100%");
    }

    @Test
    @DisplayName("Severity counts reconcile with the stored finding total")
    void severityCountsReconcile() {
        AuditEngine.AuditRunResult result = engine.run();
        int summed = findings.countBySeverity(com.auditlens.model.Severity.CRITICAL)
                   + findings.countBySeverity(com.auditlens.model.Severity.HIGH)
                   + findings.countBySeverity(com.auditlens.model.Severity.MEDIUM)
                   + findings.countBySeverity(com.auditlens.model.Severity.LOW);
        assertEquals(result.findingsRaised(), summed,
                "severity buckets must account for every finding");
    }
}
