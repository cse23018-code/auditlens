package com.auditlens;

import com.auditlens.engine.AuditEngine;
import com.auditlens.service.LedgerSeeder;
import com.auditlens.service.SnapshotExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@SpringBootApplication
public class AuditLensApplication {

    private static final Logger log = LoggerFactory.getLogger(AuditLensApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(AuditLensApplication.class, args);
    }

    /**
     * Seeds the ledger and performs the first audit run at start-up so the API
     * and dashboard have data immediately.
     *
     * <p>Passing {@code --export=<path>} additionally writes the full dashboard
     * payload to a JSON file and exits. That is what the static demo build
     * consumes, so the published demo always shows the real engine output rather
     * than hand-written sample data.</p>
     */
    @Bean
    ApplicationRunner bootstrap(LedgerSeeder seeder,
                               AuditEngine engine,
                               SnapshotExporter exporter,
                               ConfigurableApplicationContext context) {
        return (ApplicationArguments args) -> {
            seeder.seed();
            AuditEngine.AuditRunResult result = engine.run();
            log.info("Startup audit complete: run {} raised {} findings across {} controls",
                    result.runId(), result.findingsRaised(), engine.registeredRules().size());

            if (args.containsOption("export")) {
                String target = args.getOptionValues("export").get(0);
                Path written = exporter.writeTo(Path.of(target));
                log.info("Snapshot exported to {}", written.toAbsolutePath());
                System.exit(SpringApplication.exit(context, () -> 0));
            }
        };
    }
}
