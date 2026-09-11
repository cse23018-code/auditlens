package com.auditlens.repository;

import com.auditlens.model.Transaction;
import com.auditlens.model.Vendor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read access to the population under audit. The engine loads the ledger once
 * per run and shares it with every rule through the audit context.
 */
@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Transaction> TXN_MAPPER = (rs, n) -> new Transaction(
            rs.getInt("txn_id"),
            rs.getString("txn_ref"),
            rs.getInt("vendor_id"),
            rs.getString("invoice_no"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getDate("invoice_date").toLocalDate(),
            rs.getTimestamp("posted_at").toLocalDateTime(),
            rs.getInt("entered_by"),
            rs.getInt("approved_by"),
            rs.getString("cost_center"),
            rs.getString("gl_account"),
            rs.getString("payment_method"),
            rs.getString("description"),
            rs.getString("status"));

    private static final RowMapper<Vendor> VENDOR_MAPPER = (rs, n) -> new Vendor(
            rs.getInt("vendor_id"),
            rs.getString("vendor_code"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("country"),
            rs.getString("tax_id"),
            rs.getString("bank_account"),
            rs.getDate("onboarded_on").toLocalDate(),
            rs.getString("status"));

    public List<Transaction> findAll() {
        return jdbc.query("""
                SELECT * FROM transactions ORDER BY invoice_date, txn_id
                """, TXN_MAPPER);
    }

    public Transaction findById(int txnId) {
        List<Transaction> rows = jdbc.query(
                "SELECT * FROM transactions WHERE txn_id = ?", TXN_MAPPER, txnId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Vendor> findAllVendors() {
        return jdbc.query("SELECT * FROM vendors ORDER BY vendor_id", VENDOR_MAPPER);
    }

    public Map<Integer, Vendor> vendorsById() {
        return findAllVendors().stream().collect(Collectors.toMap(
                Vendor::vendorId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    public int countTransactions() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        return n == null ? 0 : n;
    }
}
