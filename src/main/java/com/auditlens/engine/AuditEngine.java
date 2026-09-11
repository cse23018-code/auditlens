package com.auditlens.engine;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.Transaction;
import com.auditlens.model.Vendor;
import com.auditlens.repository.FindingRepository;
import com.auditlens.repository.TransactionRepository;
import com.auditlens.rules.AuditContext;
import com.auditlens.rules.AuditRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates an audit run.
 *
 * <p>Spring injects every {@link AuditRule} on the classpath, so the engine has
 * no compile-time knowledge of the individual controls: adding a rule is a
 * matter of dropping in one new class. Each rule is isolated, so a control that
 * throws is logged and skipped rather than failing the whole run.</p>
 */
@Service
public class AuditEngine {

    private static final Logger log = LoggerFactory.getLogger(AuditEngine.class);
    public static final String ENGINE_VERSION = "1.0.0";

    private final List<AuditRule> rules;
    private final TransactionRepository transactions;
    private final FindingRepository findings;
    private final JdbcTemplate jdbc;

    public AuditEngine(List<AuditRule> rules,
                       TransactionRepository transactions,
                       FindingRepository findings,
                       JdbcTemplate jdbc) {
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt(AuditRule::order).thenComparing(AuditRule::code))
                .toList();
        this.transactions = transactions;
        this.findings = findings;
        this.jdbc = jdbc;
        log.info("AuditEngine initialised with {} controls: {}", this.rules.size(),
                this.rules.stream().map(AuditRule::code).toList());
    }

    public List<AuditRule> registeredRules() {
        return rules;
    }

    /**
     * Executes every registered control against the current ledger and persists
     * the resulting findings under a new run id.
     */
    @Transactional
    public AuditRunResult run() {
        long start = System.nanoTime();
        int runId = findings.openRun(ENGINE_VERSION);
        findings.clearFindings();

        List<Transaction> population = transactions.findAll();
        Map<Integer, Vendor> vendorsById = transactions.vendorsById();
        AuditContext ctx = new AuditContext(jdbc, population, vendorsById);

        List<AuditFinding> all = new ArrayList<>();
        Map<String, Integer> perRule = new LinkedHashMap<>();

        for (AuditRule rule : rules) {
            long ruleStart = System.nanoTime();
            try {
                List<AuditFinding> raised = rule.execute(ctx);
                all.addAll(raised);
                perRule.put(rule.code(), raised.size());
                log.info("{} raised {} finding(s) in {} ms", rule.code(), raised.size(),
                        (System.nanoTime() - ruleStart) / 1_000_000);
            } catch (RuntimeException ex) {
                perRule.put(rule.code(), -1);
                log.error("Control {} failed and was skipped: {}", rule.code(), ex.getMessage(), ex);
            }
        }

        findings.saveAll(runId, all);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        findings.closeRun(runId, population.size(), all.size(), durationMs);

        log.info("Audit run {} complete: {} transactions scanned, {} findings, {} ms",
                runId, population.size(), all.size(), durationMs);

        return new AuditRunResult(runId, population.size(), all.size(), durationMs,
                ENGINE_VERSION, perRule);
    }

    /** Summary of a completed run, returned by the API. */
    public record AuditRunResult(
            int runId,
            int transactionsScanned,
            int findingsRaised,
            long durationMs,
            String engineVersion,
            Map<String, Integer> findingsByRule) {
    }
}
