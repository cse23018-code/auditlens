package com.auditlens.web;

import com.auditlens.engine.AuditEngine;
import com.auditlens.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST surface of the platform.
 *
 * <pre>
 * GET  /api/dashboard                     complete dashboard payload
 * GET  /api/headline                      KPI tiles only
 * GET  /api/findings?severity=&rule=&limit=  filtered exception list
 * GET  /api/vendors/risk?limit=           vendor risk league table
 * GET  /api/vendors/{id}/transactions     drill-down detail
 * GET  /api/benford                       leading-digit distribution
 * GET  /api/rules                         registered control catalogue
 * POST /api/audit/run                     re-execute the engine
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class AuditController {

    private final DashboardService dashboard;
    private final AuditEngine engine;

    public AuditController(DashboardService dashboard, AuditEngine engine) {
        this.dashboard = dashboard;
        this.engine = engine;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return dashboard.fullSnapshot();
    }

    @GetMapping("/headline")
    public Map<String, Object> headline() {
        return dashboard.headline();
    }

    @GetMapping("/findings")
    public List<Map<String, Object>> findings(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String rule,
            @RequestParam(defaultValue = "200") int limit) {
        return dashboard.findings(severity, rule, Math.min(Math.max(limit, 1), 1000));
    }

    @GetMapping("/vendors/risk")
    public List<Map<String, Object>> vendorRisk(@RequestParam(defaultValue = "15") int limit) {
        return dashboard.vendorRisk(Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/vendors/{vendorId}/transactions")
    public ResponseEntity<List<Map<String, Object>>> vendorTransactions(
            @PathVariable int vendorId,
            @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows =
                dashboard.vendorTransactions(vendorId, Math.min(Math.max(limit, 1), 500));
        return rows.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(rows);
    }

    @GetMapping("/benford")
    public List<Map<String, Object>> benford() {
        return dashboard.benfordSeries();
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> rules() {
        return dashboard.ruleCatalogue();
    }

    @PostMapping("/audit/run")
    public AuditEngine.AuditRunResult runAudit() {
        return engine.run();
    }
}
