package com.messaging.cloudserver;

import io.micronaut.runtime.Micronaut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cloud Server Application
 * Micronaut-based replacement for Python cloud-test-server
 */
public class Application {
    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("=".repeat(70));
        LOG.info("  CLOUD Test Server (Micronaut)");
        LOG.info("=".repeat(70));
        LOG.info("  Port:          8080");
        LOG.info("  Endpoint:      http://localhost:8080/pipe/poll");
        LOG.info("=".repeat(70));
        LOG.info("");
        LOG.info("Test URLs:");
        LOG.info("  - Health:   http://localhost:8080/health");
        LOG.info("  - Status:   http://localhost:8080/status");
        LOG.info("  - Registry: http://localhost:8080/registry/topology?nodeId=local-001");
        LOG.info("  - Poll:     http://localhost:8080/pipe/poll?offset=0&limit=10");
        LOG.info("");
        LOG.info("=".repeat(70));
        LOG.info("Starting server...");
        LOG.info("=".repeat(70));

        Micronaut.run(Application.class, args);
    }
}
