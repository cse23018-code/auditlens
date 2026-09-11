package com.auditlens.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds the synthetic accounts-payable ledger the platform audits.
 *
 * <p>The generator is seeded with a fixed value so the dataset, and therefore
 * every finding and every number on the dashboard, is byte-for-byte reproducible
 * across restarts and machines. Alongside ordinary trading activity it plants a
 * known set of control breaches, which doubles as the fixture the rule tests
 * assert against.</p>
 */
@Service
public class LedgerSeeder {

    private static final Logger log = LoggerFactory.getLogger(LedgerSeeder.class);
    private static final long SEED = 20260911L;

    private static final int VENDOR_COUNT = 140;
    private static final int EMPLOYEE_COUNT = 45;
    private static final int BASE_TXN_COUNT = 5_000;

    private static final LocalDate PERIOD_START = LocalDate.of(2024, 4, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 3, 31);

    private static final String[] CATEGORIES = {
            "IT Hardware", "Professional Services", "Facilities", "Logistics",
            "Marketing", "Travel", "Software Licences", "Contract Staffing",
            "Utilities", "Office Supplies"
    };
    private static final String[] COUNTRIES = {
            "India", "Singapore", "United Kingdom", "Germany", "United States", "UAE"
    };
    private static final String[] VENDOR_STEMS = {
            "Aarav", "Northwind", "Meridian", "Cobalt", "Trident", "Solstice", "Kestrel",
            "Ironwood", "Vantage", "Lumen", "Beacon", "Sterling", "Quantum", "Redwood",
            "Anchor", "Zenith", "Pinnacle", "Crestline", "Halcyon", "Emberly", "Sandrift",
            "Oakmont", "Blueridge", "Falcon", "Granite", "Harbour", "Juniper", "Keystone"
    };
    private static final String[] VENDOR_SUFFIXES = {
            "Technologies", "Solutions", "Enterprises", "Industries", "Systems",
            "Associates", "Logistics", "Partners", "Services", "Consulting"
    };
    private static final String[] FIRST_NAMES = {
            "Ananya", "Rohan", "Priya", "Vikram", "Meera", "Arjun", "Kavya", "Siddharth",
            "Neha", "Karthik", "Ishita", "Rahul", "Divya", "Aditya", "Sneha", "Nikhil",
            "Pooja", "Manish", "Ritika", "Suresh", "Tara", "Varun", "Anjali", "Deepak"
    };
    private static final String[] LAST_NAMES = {
            "Sharma", "Iyer", "Nair", "Verma", "Reddy", "Bose", "Gupta", "Menon",
            "Chopra", "Rao", "Das", "Kulkarni", "Sinha", "Patel", "Joshi", "Bhatt"
    };
    private static final String[] DEPARTMENTS = {
            "Finance", "Procurement", "Operations", "IT", "Shared Services"
    };
    private static final String[] ROLES = {
            "AP Clerk", "AP Analyst", "Finance Manager", "Procurement Lead",
            "Controller", "Cost Accountant"
    };
    private static final String[] PAYMENT_METHODS = { "NEFT", "RTGS", "WIRE", "CHEQUE", "ACH" };
    private static final String[] GL_ACCOUNTS = {
            "5010-COGS", "5200-OPEX", "5310-IT", "5400-TRAVEL", "5500-MKTG", "5610-FAC"
    };

    private final JdbcTemplate jdbc;
    private final Random rnd = new Random(SEED);

    public LedgerSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void seed() {
        long start = System.currentTimeMillis();
        seedEmployees();
        seedVendors();
        int txnId = seedTransactions();
        seedBankChanges();
        plantControlBreaches(txnId);
        log.info("Seeded ledger: {} vendors, {} employees, {} transactions in {} ms",
                VENDOR_COUNT, EMPLOYEE_COUNT,
                jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class),
                System.currentTimeMillis() - start);
    }

    // -----------------------------------------------------------------
    // Master data
    // -----------------------------------------------------------------
    private void seedEmployees() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= EMPLOYEE_COUNT; i++) {
            String role = ROLES[rnd.nextInt(ROLES.length)];
            BigDecimal limit = switch (role) {
                case "Controller"       -> new BigDecimal("1000000");
                case "Finance Manager"  -> new BigDecimal("500000");
                case "Procurement Lead" -> new BigDecimal("250000");
                case "AP Analyst"       -> new BigDecimal("100000");
                default                 -> new BigDecimal("50000");
            };
            rows.add(new Object[]{
                    i,
                    "EMP-%04d".formatted(i),
                    FIRST_NAMES[rnd.nextInt(FIRST_NAMES.length)] + " " + LAST_NAMES[rnd.nextInt(LAST_NAMES.length)],
                    DEPARTMENTS[rnd.nextInt(DEPARTMENTS.length)],
                    role,
                    limit
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO employees
                    (employee_id, employee_code, name, department, job_role, approval_limit)
                VALUES (?,?,?,?,?,?)
                """, rows);
    }

    private void seedVendors() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= VENDOR_COUNT; i++) {
            String name = VENDOR_STEMS[rnd.nextInt(VENDOR_STEMS.length)] + " "
                    + VENDOR_SUFFIXES[rnd.nextInt(VENDOR_SUFFIXES.length)];
            if (i > VENDOR_STEMS.length) {
                name = name + " " + (char) ('A' + rnd.nextInt(26));
            }
            rows.add(new Object[]{
                    i,
                    "VEN-%04d".formatted(i),
                    name,
                    CATEGORIES[rnd.nextInt(CATEGORIES.length)],
                    COUNTRIES[rnd.nextInt(COUNTRIES.length)],
                    "TAX%08d".formatted(100000 + rnd.nextInt(899999)),
                    "ACC%012d".formatted(Math.abs(rnd.nextLong() % 900000000000L) + 100000000000L),
                    Date.valueOf(PERIOD_START.minusDays(rnd.nextInt(1800))),
                    rnd.nextInt(100) < 6 ? "DORMANT" : "ACTIVE"
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO vendors
                    (vendor_id, vendor_code, name, category, country, tax_id,
                     bank_account, onboarded_on, status)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, rows);
    }

    // -----------------------------------------------------------------
    // Transaction population
    // -----------------------------------------------------------------
    private int seedTransactions() {
        long spanDays = PERIOD_END.toEpochDay() - PERIOD_START.toEpochDay();
        List<Object[]> rows = new ArrayList<>();
        int txnId = 0;

        for (int i = 0; i < BASE_TXN_COUNT; i++) {
            txnId++;
            int vendorId = weightedVendor();
            LocalDate invoiceDate = PERIOD_START.plusDays((long) (rnd.nextDouble() * spanDays));
            // Log-normal amounts give a realistic long-tailed spend profile that
            // naturally satisfies Benford's Law.
            double magnitude = Math.exp(7.5 + rnd.nextGaussian() * 1.15);
            BigDecimal amount = BigDecimal.valueOf(magnitude)
                    .setScale(2, RoundingMode.HALF_UP)
                    .max(new BigDecimal("500.00"));

            int enteredBy = 1 + rnd.nextInt(EMPLOYEE_COUNT);
            int approvedBy = 1 + rnd.nextInt(EMPLOYEE_COUNT);
            LocalDateTime postedAt = businessHoursTimestamp(invoiceDate);

            rows.add(new Object[]{
                    txnId,
                    "TXN-%06d".formatted(txnId),
                    vendorId,
                    "INV-%d-%05d".formatted(invoiceDate.getYear(), 10000 + rnd.nextInt(89999)),
                    amount,
                    "INR",
                    Date.valueOf(invoiceDate),
                    Timestamp.valueOf(postedAt),
                    enteredBy,
                    approvedBy,
                    "CC-%03d".formatted(100 + rnd.nextInt(40)),
                    GL_ACCOUNTS[rnd.nextInt(GL_ACCOUNTS.length)],
                    PAYMENT_METHODS[rnd.nextInt(PAYMENT_METHODS.length)],
                    "Supplier invoice settlement",
                    rnd.nextInt(100) < 2 ? "REVERSED" : "POSTED"
            });
        }
        insertTransactions(rows);
        return txnId;
    }

    /** Spend is concentrated in a minority of suppliers, as it is in real ledgers. */
    private int weightedVendor() {
        if (rnd.nextInt(100) < 60) {
            return 1 + rnd.nextInt(VENDOR_COUNT / 5);
        }
        return 1 + rnd.nextInt(VENDOR_COUNT);
    }

    private LocalDateTime businessHoursTimestamp(LocalDate date) {
        int hour = 9 + rnd.nextInt(9);
        return date.atTime(hour, rnd.nextInt(60), rnd.nextInt(60));
    }

    private void seedBankChanges() {
        List<Object[]> rows = new ArrayList<>();
        long spanDays = PERIOD_END.toEpochDay() - PERIOD_START.toEpochDay();
        for (int i = 1; i <= 60; i++) {
            int vendorId = 1 + rnd.nextInt(VENDOR_COUNT);
            rows.add(new Object[]{
                    i,
                    vendorId,
                    Date.valueOf(PERIOD_START.plusDays((long) (rnd.nextDouble() * spanDays))),
                    "ACC%012d".formatted(Math.abs(rnd.nextLong() % 900000000000L) + 100000000000L),
                    "ACC%012d".formatted(Math.abs(rnd.nextLong() % 900000000000L) + 100000000000L),
                    1 + rnd.nextInt(EMPLOYEE_COUNT)
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO vendor_bank_changes
                    (change_id, vendor_id, changed_on, old_account, new_account, changed_by)
                VALUES (?,?,?,?,?,?)
                """, rows);
    }

    // -----------------------------------------------------------------
    // Planted control breaches
    // -----------------------------------------------------------------
    /**
     * Injects a known population of exceptions. These are what the rule engine is
     * expected to surface, and what {@code AuditEngineTest} asserts on.
     */
    private void plantControlBreaches(int startingTxnId) {
        List<Object[]> rows = new ArrayList<>();
        int txnId = startingTxnId;

        // DUP-001: exact duplicate invoices a few days apart.
        for (int i = 0; i < 18; i++) {
            int vendorId = 1 + rnd.nextInt(25);
            LocalDate d1 = PERIOD_START.plusDays(90L + rnd.nextInt(500));
            BigDecimal amount = BigDecimal.valueOf(45_000 + rnd.nextInt(400_000))
                    .setScale(2, RoundingMode.HALF_UP);
            int clerk = 1 + rnd.nextInt(EMPLOYEE_COUNT);
            for (int copy = 0; copy < 2; copy++) {
                txnId++;
                LocalDate d = copy == 0 ? d1 : d1.plusDays(1 + rnd.nextInt(6));
                rows.add(txnRow(txnId, vendorId, d, amount, clerk,
                        1 + rnd.nextInt(EMPLOYEE_COUNT), businessHoursTimestamp(d),
                        "Duplicate-risk settlement"));
            }
        }

        // SPL-002: purchases sliced under the 100,000 approval threshold.
        for (int i = 0; i < 12; i++) {
            int vendorId = 1 + rnd.nextInt(VENDOR_COUNT);
            LocalDate anchor = PERIOD_START.plusDays(60L + rnd.nextInt(600));
            int clerk = 1 + rnd.nextInt(EMPLOYEE_COUNT);
            int slices = 4 + rnd.nextInt(3);
            for (int s = 0; s < slices; s++) {
                txnId++;
                LocalDate d = anchor.plusDays(rnd.nextInt(5));
                BigDecimal amount = BigDecimal.valueOf(82_000 + rnd.nextInt(16_000))
                        .setScale(2, RoundingMode.HALF_UP);
                rows.add(txnRow(txnId, vendorId, d, amount, clerk,
                        1 + rnd.nextInt(EMPLOYEE_COUNT), businessHoursTimestamp(d),
                        "Partial delivery billing"));
            }
        }

        // SOD-003: entered and approved by the same user.
        for (int i = 0; i < 22; i++) {
            txnId++;
            int vendorId = 1 + rnd.nextInt(VENDOR_COUNT);
            LocalDate d = PERIOD_START.plusDays(rnd.nextInt(700));
            int actor = 1 + rnd.nextInt(EMPLOYEE_COUNT);
            BigDecimal amount = BigDecimal.valueOf(60_000 + rnd.nextInt(700_000))
                    .setScale(2, RoundingMode.HALF_UP);
            rows.add(txnRow(txnId, vendorId, d, amount, actor, actor,
                    businessHoursTimestamp(d), "Expedited settlement"));
        }

        // AHR-005: material postings at night and over weekends.
        for (int i = 0; i < 26; i++) {
            txnId++;
            int vendorId = 1 + rnd.nextInt(VENDOR_COUNT);
            LocalDate d = PERIOD_START.plusDays(rnd.nextInt(700));
            // Push to a Saturday for half of them, otherwise use a small hour.
            LocalDate posted = i % 2 == 0 ? d.with(java.time.DayOfWeek.SATURDAY) : d;
            LocalDateTime ts = i % 2 == 0
                    ? posted.atTime(11 + rnd.nextInt(6), rnd.nextInt(60))
                    : d.atTime(rnd.nextInt(5), rnd.nextInt(60));
            BigDecimal amount = BigDecimal.valueOf(80_000 + rnd.nextInt(500_000))
                    .setScale(2, RoundingMode.HALF_UP);
            rows.add(txnRow(txnId, vendorId, posted, amount,
                    1 + rnd.nextInt(EMPLOYEE_COUNT), 1 + rnd.nextInt(EMPLOYEE_COUNT),
                    ts, "Out-of-cycle payment run"));
        }

        // RND-006: suspiciously round material amounts.
        for (int i = 0; i < 20; i++) {
            txnId++;
            int vendorId = 1 + rnd.nextInt(VENDOR_COUNT);
            LocalDate d = PERIOD_START.plusDays(rnd.nextInt(700));
            BigDecimal amount = BigDecimal.valueOf((8L + rnd.nextInt(40)) * 10_000L)
                    .setScale(2, RoundingMode.HALF_UP);
            rows.add(txnRow(txnId, vendorId, d, amount,
                    1 + rnd.nextInt(EMPLOYEE_COUNT), 1 + rnd.nextInt(EMPLOYEE_COUNT),
                    businessHoursTimestamp(d), "Retainer fee"));
        }

        // BNF-007: one vendor billing with an unnaturally flat digit profile.
        int riggedVendor = 7;
        for (int i = 0; i < 90; i++) {
            txnId++;
            LocalDate d = PERIOD_START.plusDays(rnd.nextInt(700));
            int lead = 4 + rnd.nextInt(6);
            BigDecimal amount = BigDecimal.valueOf(lead * 10_000L + rnd.nextInt(9_999))
                    .setScale(2, RoundingMode.HALF_UP);
            rows.add(txnRow(txnId, riggedVendor, d, amount,
                    1 + rnd.nextInt(EMPLOYEE_COUNT), 1 + rnd.nextInt(EMPLOYEE_COUNT),
                    businessHoursTimestamp(d), "Managed service fee"));
        }

        // BNK-008: material payments immediately after a bank-detail change.
        List<java.util.Map<String, Object>> changes = jdbc.queryForList(
                "SELECT change_id, vendor_id, changed_on, changed_by FROM vendor_bank_changes LIMIT 14");
        for (java.util.Map<String, Object> change : changes) {
            txnId++;
            int vendorId = ((Number) change.get("vendor_id")).intValue();
            int changedBy = ((Number) change.get("changed_by")).intValue();
            LocalDate changedOn = ((java.sql.Date) change.get("changed_on")).toLocalDate();
            LocalDate d = changedOn.plusDays(1 + rnd.nextInt(10));
            BigDecimal amount = BigDecimal.valueOf(120_000 + rnd.nextInt(900_000))
                    .setScale(2, RoundingMode.HALF_UP);
            rows.add(txnRow(txnId, vendorId, d, amount, changedBy,
                    1 + rnd.nextInt(EMPLOYEE_COUNT), businessHoursTimestamp(d),
                    "Settlement to updated bank mandate"));
        }

        // DRM-009: dormant suppliers reactivated with a large payment.
        for (int i = 0; i < 10; i++) {
            int vendorId = VENDOR_COUNT - i;
            LocalDate early = PERIOD_START.plusDays(10L + rnd.nextInt(20));
            LocalDate late = early.plusDays(220L + rnd.nextInt(200));
            if (late.isAfter(PERIOD_END)) {
                late = PERIOD_END;
            }
            txnId++;
            rows.add(txnRow(txnId, vendorId, early,
                    BigDecimal.valueOf(15_000 + rnd.nextInt(20_000)).setScale(2, RoundingMode.HALF_UP),
                    1 + rnd.nextInt(EMPLOYEE_COUNT), 1 + rnd.nextInt(EMPLOYEE_COUNT),
                    businessHoursTimestamp(early), "Initial engagement"));
            txnId++;
            rows.add(txnRow(txnId, vendorId, late,
                    BigDecimal.valueOf(200_000 + rnd.nextInt(600_000)).setScale(2, RoundingMode.HALF_UP),
                    1 + rnd.nextInt(EMPLOYEE_COUNT), 1 + rnd.nextInt(EMPLOYEE_COUNT),
                    businessHoursTimestamp(late), "Reactivation settlement"));
        }

        insertTransactions(rows);
        log.info("Planted {} control-breach transactions for detection", rows.size());
    }

    private Object[] txnRow(int txnId, int vendorId, LocalDate invoiceDate, BigDecimal amount,
                            int enteredBy, int approvedBy, LocalDateTime postedAt, String description) {
        return new Object[]{
                txnId,
                "TXN-%06d".formatted(txnId),
                vendorId,
                "INV-%d-%05d".formatted(invoiceDate.getYear(), 10000 + rnd.nextInt(89999)),
                amount,
                "INR",
                Date.valueOf(invoiceDate),
                Timestamp.valueOf(postedAt),
                enteredBy,
                approvedBy,
                "CC-%03d".formatted(100 + rnd.nextInt(40)),
                GL_ACCOUNTS[rnd.nextInt(GL_ACCOUNTS.length)],
                PAYMENT_METHODS[rnd.nextInt(PAYMENT_METHODS.length)],
                description,
                "POSTED"
        };
    }

    private void insertTransactions(List<Object[]> rows) {
        jdbc.batchUpdate("""
                INSERT INTO transactions
                    (txn_id, txn_ref, vendor_id, invoice_no, amount, currency, invoice_date,
                     posted_at, entered_by, approved_by, cost_center, gl_account,
                     payment_method, description, status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, rows);
    }
}
