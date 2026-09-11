package com.auditlens.model;

import java.time.LocalDate;

public record Vendor(
        int vendorId,
        String vendorCode,
        String name,
        String category,
        String country,
        String taxId,
        String bankAccount,
        LocalDate onboardedOn,
        String status) {
}
