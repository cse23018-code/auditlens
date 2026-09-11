package com.auditlens.model;

import java.math.BigDecimal;

public record Employee(
        int employeeId,
        String employeeCode,
        String name,
        String department,
        String jobRole,
        BigDecimal approvalLimit) {
}
