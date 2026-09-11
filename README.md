# AuditLens

**Forensic Transaction Audit & Risk Analytics Platform**

A Java and SQL engine that scans an enterprise accounts-payable ledger against ten
independent internal controls, quantifies the monetary exposure behind each
exception, scores supplier risk, and serves the results through a REST API and an
analytics dashboard.

> **Live demo:** https://cse23018-code.github.io/auditlens/
> **Source:** https://github.com/cse23018-code/auditlens

---

## Why this exists

Finance teams process tens of thousands of supplier invoices a year. Manual review
samples a fraction of one percent of them. This project takes the controls an
auditor would test by hand and expresses each one as code that runs over the whole
population, so nothing is sampled away.

On the seeded dataset it scans **5,294 transactions against 10 controls in roughly
one second** and raises **804 exceptions** carrying a combined flagged exposure of
about ₹16.2 crore.

---

## The controls

Each control is an independent, self-registering Java class. Set-based tests push
their logic into SQL; statistical tests operate over the population in Java.

| Code | Control | Domain | Severity | Technique |
|------|---------|--------|----------|-----------|
| `DUP-001` | Duplicate Payment Detection | Payment Integrity | Critical | SQL self-join on a date window |
| `SPL-002` | Split Invoice / Threshold Circumvention | Authorisation | High | Recursive CTE clustering |
| `SOD-003` | Segregation of Duties Breach | Segregation of Duties | Critical | SQL predicate join |
| `LMT-004` | Approval Authority Exceeded | Authorisation | High | Join against delegated limits |
| `AHR-005` | Out-of-Hours Posting | Payment Integrity | Medium | Calendar analysis in Java |
| `RND-006` | Round-Amount Anomaly | Statistical Anomaly | Medium | Modulo arithmetic in SQL |
| `BNF-007` | Benford's Law Digit Anomaly | Statistical Anomaly | High | Chi-square goodness-of-fit |
| `BNK-008` | Payment After Bank-Detail Change | Vendor Master Data | Critical | Temporal join to the change log |
| `DRM-009` | Dormant Vendor Reactivation | Vendor Master Data | Medium | `LAG()` window function |
| `OUT-010` | Vendor Baseline Outlier | Statistical Anomaly | Medium | Windowed mean and standard deviation |

### Benford's Law in one paragraph

In naturally occurring financial data the leading digit is not uniform: about 30%
of values begin with a 1 and only 4.6% with a 9, following
`P(d) = log₁₀(1 + 1/d)`. Fabricated figures rarely reproduce that curve, because
people inventing numbers spread the leading digits far more evenly. `BNF-007`
computes a Pearson chi-square statistic per vendor and reports any supplier above
the 99% critical value of 20.09 at 8 degrees of freedom.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Dashboard (vanilla JS, hand-rolled SVG charts, no build)    │
└───────────────────────────┬──────────────────────────────────┘
                            │ REST / JSON
┌───────────────────────────▼──────────────────────────────────┐
│  AuditController        REST surface, filtering, drill-down  │
│  DashboardService       payload assembly, key normalisation  │
│  AuditEngine            rule discovery, isolation, scoring   │
│  AuditRule × 10         one class per control                │
│  Repositories           JdbcTemplate, batched writes         │
└───────────────────────────┬──────────────────────────────────┘
                            │ JDBC
┌───────────────────────────▼──────────────────────────────────┐
│  H2 (in-memory) - ANSI SQL, portable to PostgreSQL/Oracle    │
│  6 tables · 3 views · CTEs · window functions · indexes      │
└──────────────────────────────────────────────────────────────┘
```

**Extensibility.** `AuditEngine` receives `List<AuditRule>` by constructor
injection, so it has no compile-time knowledge of any individual control. Adding
an eleventh control means adding one `@Component` class — no registry, no switch
statement, no change to the engine. Each control is executed in isolation, so one
that throws is logged and skipped instead of failing the run.

**Portability.** JDBC reports column labels in the database's own case (upper on
H2 and Oracle, lower on PostgreSQL). `DashboardService` normalises them to
camelCase, so the published API contract stays identical whichever engine is
behind it.

---

## Database design

Six tables — `vendors`, `employees`, `transactions`, `vendor_bank_changes`,
`audit_runs`, `audit_findings` — with foreign keys, check constraints, four
indexes on the transaction hot paths, and three reporting views.

The analytics layer is deliberately SQL-first: aggregation happens in the
database, and the Java layer stays a thin pass-through. Techniques used include
common table expressions, `LAG()`, `RANK()`, `STDDEV_POP()`, windowed running
totals, and conditional aggregation.

```sql
-- OUT-010: per-vendor baseline built with window functions
WITH baseline AS (
    SELECT t.txn_id, t.vendor_id, t.amount,
           AVG(t.amount)        OVER (PARTITION BY t.vendor_id) AS vendor_mean,
           STDDEV_POP(t.amount) OVER (PARTITION BY t.vendor_id) AS vendor_sd,
           COUNT(*)             OVER (PARTITION BY t.vendor_id) AS vendor_n
    FROM   transactions t
    WHERE  t.status <> 'REVERSED'
)
SELECT b.*, (b.amount - b.vendor_mean) / b.vendor_sd AS z_score
FROM   baseline b
WHERE  b.vendor_n >= 12 AND b.vendor_sd > 0
  AND  (b.amount - b.vendor_mean) / b.vendor_sd > 3.0;
```

---

## API

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET`  | `/api/dashboard` | Complete dashboard payload |
| `GET`  | `/api/headline` | KPI tiles |
| `GET`  | `/api/findings?severity=&rule=&limit=` | Filtered exception register |
| `GET`  | `/api/vendors/risk?limit=` | Vendor risk league table |
| `GET`  | `/api/vendors/{id}/transactions` | Drill-down detail |
| `GET`  | `/api/benford` | Leading-digit distribution |
| `GET`  | `/api/rules` | Registered control catalogue |
| `POST` | `/api/audit/run` | Re-execute the engine |
| `GET`  | `/actuator/health` | Health probe |

---

## Running it

```bash
# requires JDK 21+ and Maven
mvn spring-boot:run
# dashboard  http://localhost:8080
# H2 console http://localhost:8080/h2-console   (jdbc:h2:mem:auditlens, user sa)
```

```bash
mvn test          # 13 tests
mvn package       # executable JAR
docker build -t auditlens . && docker run -p 8080:8080 auditlens
```

Export a dashboard snapshot as JSON (this is how the static demo is built, so the
published page shows real engine output rather than mock data):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--export=docs/snapshot.json"
```

---

## Testing

13 tests across two suites:

- **`BenfordDigitRuleTest`** — the statistical core. Verifies the Benford
  frequencies against published values, that they sum to 1, that leading-digit
  extraction is scale-invariant, and that a conforming population scores near zero
  chi-square while a uniform one is flagged.
- **`AuditEngineIntegrationTest`** — full Spring context. Asserts that all ten
  controls are discovered with unique codes, that **every** control detects the
  breaches planted by the seeder, that repeat runs replace rather than accumulate
  findings, that severity buckets reconcile to the run total, and that the API
  payload is complete and camelCased.

The ledger generator is seeded with a fixed value, so the dataset, every finding,
and every figure on the dashboard are reproducible across machines.

---

## Tech stack

Java 21 · Spring Boot 3.5 · Spring JDBC · H2 · JUnit 5 · Maven · Docker ·
vanilla JS with hand-rolled SVG charts (no frontend dependencies)

Chart colours are drawn from a palette validated for colour-vision deficiency;
severity is always encoded with an icon and a label as well as colour, never by
colour alone.

---

## A note on the data

The ledger is **synthetic**, generated from a fixed seed. All vendor and employee
names are fictitious. Alongside ordinary trading activity the generator plants a
known population of control breaches, which is what the engine is expected to
surface and what the integration tests assert against.

---

Built by **Ankit Kumar** — B.Tech Computer Science & Engineering, IIIT Kalyani.
