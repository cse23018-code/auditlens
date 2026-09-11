package com.auditlens.rules;

import com.auditlens.model.AuditFinding;
import com.auditlens.model.ControlDomain;
import com.auditlens.model.Severity;

import java.util.List;

/**
 * A single forensic audit control.
 *
 * <p>Rules are discovered by Spring at start-up and executed by the
 * {@code AuditEngine}; adding a new control means adding one
 * {@code @Component} implementing this interface and nothing else.
 * This keeps the engine closed for modification but open for extension.</p>
 */
public interface AuditRule {

    /** Stable short code used in reports and the UI, e.g. {@code DUP-001}. */
    String code();

    /** Human-readable control name. */
    String title();

    /** What the control is testing for, in plain English. */
    String description();

    /** Control grouping used for reporting. */
    ControlDomain domain();

    /** Severity assigned to findings this rule raises. */
    Severity severity();

    /** Execution order; lower runs first. */
    default int order() {
        return 100;
    }

    /** Run the control over the population and return any exceptions found. */
    List<AuditFinding> execute(AuditContext ctx);
}
