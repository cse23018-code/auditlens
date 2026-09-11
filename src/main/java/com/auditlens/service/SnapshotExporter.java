package com.auditlens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes the dashboard payload to a JSON file.
 *
 * <p>The published static demo is built from this export, so the figures on the
 * hosted page are produced by the same engine and the same SQL that serve the
 * live API - the demo is a snapshot of a real run, not mock data.</p>
 */
@Service
public class SnapshotExporter {

    private final DashboardService dashboard;
    private final ObjectMapper mapper;

    public SnapshotExporter(DashboardService dashboard) {
        this.dashboard = dashboard;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path writeTo(Path target) throws IOException {
        Map<String, Object> snapshot = dashboard.fullSnapshot();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        mapper.writeValue(target.toFile(), snapshot);
        return target;
    }

    public String toJson() throws IOException {
        return mapper.writeValueAsString(dashboard.fullSnapshot());
    }
}
