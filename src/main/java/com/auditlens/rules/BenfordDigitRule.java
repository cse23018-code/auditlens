package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;
import com.auditlens.model.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BNF-007 - Benford's Law first-digit anomaly.
 *
 * <p>In a naturally occurring set of invoice values the leading digit follows
 * Benford's distribution, P(d) = log10(1 + 1/d): roughly 30% of amounts start
 * with a 1 and only 4.6% start with a 9. Fabricated or manipulated amounts tend
 * not to, because people inventing numbers spread the leading digits far more
 * evenly than nature does.</p>
 *
 * <p>For every vendor with a statistically usable sample this control computes a
 * Pearson chi-square goodness-of-fit statistic against the Benford expectation.
 * At 8 degrees of freedom the 99% critical value is 20.09, so anything above
 * that is reported as a population worth pulling for substantive testing.</p>
 */
@Component
public class BenfordDigitRule extends AbstractAuditRule {

    /** Chi-square critical value, 8 degrees of freedom, 99% confidence. */
    private static final double CHI_SQUARE_CRITICAL = 20.09;

    /** Minimum sample size below which the statistic is not meaningful. */
    private static final int MIN_SAMPLE = 30;

    @Override public String code()          { return "BNF-007"; }
    @Override public String title()         { return "Benford's Law Digit Anomaly"; }
    @Override public String description()   { return "Vendor invoice values whose leading-digit distribution fails a chi-square fit against Benford's Law."; }
    @Override public ControlDomain domain() { return ControlDomain.STATISTICAL_ANOMALY; }
    @Override public Severity severity()    { return Severity.HIGH; }
    @Override public int order()            { return 70; }

    /** Expected Benford frequency for leading digit d (1-9). */
    public static double expectedFrequency(int digit) {
        return Math.log10(1.0 + 1.0 / digit);
    }

    /** Leading significant digit of a positive amount, or 0 if undefined. */
    public static int leadingDigit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return 0;
        }
        String digits = amount.abs().toPlainString().replace(".", "");
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c > '0' && c <= '9') {
                return c - '0';
            }
        }
        return 0;
    }

    /**
     * Pearson chi-square statistic of an observed leading-digit histogram
     * against the Benford expectation. Index 1..9 of {@code observed} holds the
     * count for that digit.
     */
    public static double chiSquare(int[] observed, int sampleSize) {
        if (sampleSize <= 0) {
            return 0.0;
        }
        double chi = 0.0;
        for (int d = 1; d <= 9; d++) {
            double expected = expectedFrequency(d) * sampleSize;
            double delta = observed[d] - expected;
            chi += (delta * delta) / expected;
        }
        return chi;
    }

    @Override
    public List<AuditFinding> execute(AuditContext ctx) {
        Map<Integer, int[]> histogramByVendor = new LinkedHashMap<>();
        Map<Integer, Integer> sampleByVendor = new LinkedHashMap<>();
        Map<Integer, BigDecimal> spendByVendor = new LinkedHashMap<>();

        for (Transaction t : ctx.transactions()) {
            int digit = leadingDigit(t.amount());
            if (digit == 0) {
                continue;
            }
            histogramByVendor.computeIfAbsent(t.vendorId(), k -> new int[10])[digit]++;
            sampleByVendor.merge(t.vendorId(), 1, Integer::sum);
            spendByVendor.merge(t.vendorId(), t.amount(), BigDecimal::add);
        }

        List<AuditFinding> findings = new ArrayList<>();
        for (Map.Entry<Integer, int[]> entry : histogramByVendor.entrySet()) {
            int vendorId = entry.getKey();
            int sample = sampleByVendor.getOrDefault(vendorId, 0);
            if (sample < MIN_SAMPLE) {
                continue;
            }
            double chi = chiSquare(entry.getValue(), sample);
            if (chi <= CHI_SQUARE_CRITICAL) {
                continue;
            }

            int[] observed = entry.getValue();
            int worstDigit = 1;
            double worstDelta = 0.0;
            for (int d = 1; d <= 9; d++) {
                double expected = expectedFrequency(d) * sample;
                double delta = Math.abs(observed[d] - expected);
                if (delta > worstDelta) {
                    worstDelta = delta;
                    worstDigit = d;
                }
            }
            double observedPct = 100.0 * observed[worstDigit] / sample;
            double expectedPct = 100.0 * expectedFrequency(worstDigit);

            String summary = "%s invoice values deviate from Benford's Law (chi-square %.1f)".formatted(
                    ctx.vendorName(vendorId), chi);
            String evidence = ("Across %d invoices, %.1f%% begin with the digit %d against an expected %.1f%%. "
                    + "Chi-square %.2f exceeds the 99%% critical value of %.2f at 8 degrees of freedom.")
                    .formatted(sample, observedPct, worstDigit, expectedPct, chi, CHI_SQUARE_CRITICAL);

            findings.add(finding(vendorId, null,
                    spendByVendor.getOrDefault(vendorId, BigDecimal.ZERO),
                    scoreFor(chi, CHI_SQUARE_CRITICAL), summary, evidence));
        }
        return findings;
    }
}
