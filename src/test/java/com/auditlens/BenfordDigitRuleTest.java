package com.auditlens;

import com.auditlens.rules.BenfordDigitRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the statistical core of BNF-007. The maths is pure, so it is
 * tested directly rather than through the engine.
 */
class BenfordDigitRuleTest {

    @Test
    @DisplayName("Benford expectation matches the published frequencies")
    void expectedFrequenciesAreCorrect() {
        assertEquals(0.3010, BenfordDigitRule.expectedFrequency(1), 0.0001);
        assertEquals(0.1761, BenfordDigitRule.expectedFrequency(2), 0.0001);
        assertEquals(0.0458, BenfordDigitRule.expectedFrequency(9), 0.0001);
    }

    @Test
    @DisplayName("Expected frequencies sum to one across digits 1-9")
    void expectedFrequenciesSumToOne() {
        double total = 0.0;
        for (int d = 1; d <= 9; d++) {
            total += BenfordDigitRule.expectedFrequency(d);
        }
        assertEquals(1.0, total, 1e-9);
    }

    @Test
    @DisplayName("Leading digit ignores scale, decimals and separators")
    void leadingDigitIsScaleInvariant() {
        assertEquals(4, BenfordDigitRule.leadingDigit(new BigDecimal("451.20")));
        assertEquals(4, BenfordDigitRule.leadingDigit(new BigDecimal("45120000")));
        assertEquals(9, BenfordDigitRule.leadingDigit(new BigDecimal("0.00912")));
        assertEquals(1, BenfordDigitRule.leadingDigit(new BigDecimal("1000000")));
    }

    @Test
    @DisplayName("Leading digit is undefined for zero and null")
    void leadingDigitHandlesEdgeCases() {
        assertEquals(0, BenfordDigitRule.leadingDigit(BigDecimal.ZERO));
        assertEquals(0, BenfordDigitRule.leadingDigit(null));
    }

    @Test
    @DisplayName("A perfectly Benford population scores near zero chi-square")
    void conformingPopulationPassesTest() {
        int sample = 10_000;
        int[] observed = new int[10];
        for (int d = 1; d <= 9; d++) {
            observed[d] = (int) Math.round(BenfordDigitRule.expectedFrequency(d) * sample);
        }
        double chi = BenfordDigitRule.chiSquare(observed, sample);
        assertTrue(chi < 1.0, "conforming population should not be flagged, got " + chi);
    }

    @Test
    @DisplayName("A uniform population is flagged as non-conforming")
    void uniformPopulationFailsTest() {
        int sample = 900;
        int[] observed = new int[10];
        for (int d = 1; d <= 9; d++) {
            observed[d] = sample / 9;       // flat distribution, not Benford
        }
        double chi = BenfordDigitRule.chiSquare(observed, sample);
        assertTrue(chi > 20.09,
                "uniform population should exceed the 99% critical value, got " + chi);
    }
}
