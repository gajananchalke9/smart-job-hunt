package com.smartjobhunt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Entry point for the Smart Job Hunt Spring Boot application.
 *
 * <p>Exposes REST endpoints for:
 * <ul>
 *   <li>Uploading job-description PDFs to GCS and indexing them in Vertex AI Search</li>
 *   <li>Searching job profiles via natural-language queries</li>
 *   <li>Matching a resume against indexed job profiles using Gemini explainable scoring</li>
 * </ul>
 */
@SpringBootApplication
public class SmartJobHuntApplication {

    private static final Logger log = LoggerFactory.getLogger(SmartJobHuntApplication.class);

    public static void main(String[] args) {
        log.info("Starting Smart Job Hunt application...");
        SpringApplication.run(SmartJobHuntApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("========================================");
        log.info("Smart Job Hunt application is ready!");
        log.info("Swagger UI: http://localhost:8080/swagger-ui.html");
        log.info("API Docs: http://localhost:8080/v3/api-docs");
        log.info("========================================");
    }
}
