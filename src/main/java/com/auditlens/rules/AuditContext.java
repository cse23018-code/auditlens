package com.auditlens.rules;

import com.auditlens.model.Transaction;
import com.auditlens.model.Vendor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Everything a rule needs to execute, assembled once per audit run.
 *
 * <p>Set-based rules push the detection logic down into SQL via {@link #jdbc()}.
 * Statistical rules that need whole-population maths (e.g. Benford's Law) work
 * against the pre-loaded in-memory collections instead, so the population is
 * read from the database exactly once per run.</p>
 */
public record AuditContext(
        JdbcTemplate jdbc,
        List<Transaction> transactions,
        Map<Integer, Vendor> vendorsById) {

    public String vendorName(int vendorId) {
        Vendor v = vendorsById.get(vendorId);
        return v == null ? "Vendor #" + vendorId : v.name();
    }
}
