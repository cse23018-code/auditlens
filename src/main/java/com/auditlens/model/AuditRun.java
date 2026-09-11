package com.auditlens.model;

import java.time.LocalDateTime;

public record AuditRun(
        int runId,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        int txnsScanned,
        int findingsCount,
        long durationMs,
        String engineVersion) {
}
