package com.auditlens.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record Transaction(
        int txnId,
        String txnRef,
        int vendorId,
        String invoiceNo,
        BigDecimal amount,
        String currency,
        LocalDate invoiceDate,
        LocalDateTime postedAt,
        int enteredBy,
        int approvedBy,
        String costCenter,
        String glAccount,
        String paymentMethod,
        String description,
        String status) {
}
