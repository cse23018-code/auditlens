package com.auditlens.model;

import java.time.LocalDate;

public record BankChange(
        int changeId,
        int vendorId,
        LocalDate changedOn,
        String oldAccount,
        String newAccount,
        int changedBy) {
}
