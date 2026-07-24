package com.smartjobhunt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${gcp.project-id}")
    private String projectId;

    @Value("${gcp.gcs.bucket}")
    private String bucketName;

    @Value("${gcp.discovery-engine.datastore-id}")
    private String datastoreId;

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
        
        // Validate configuration
        validateConfiguration();
    }

    private void validateConfiguration() {
        log.info("Validating GCP configuration...");
        
        boolean hasErrors = false;
        
        if (isInvalidConfig(projectId)) {
            log.error("❌ GCP Project ID is not configured! Please set gcp.project-id in application.yml or via environment variable GCP_PROJECT_ID");
            hasErrors = true;
        } else {
            log.info("✓ GCP Project ID: {}", projectId);
        }
        
        if (isInvalidConfig(bucketName)) {
            log.error("❌ GCS Bucket is not configured! Please set gcp.gcs.bucket in application.yml or via environment variable GCP_GCS_BUCKET");
            hasErrors = true;
        } else {
            log.info("✓ GCS Bucket: {}", bucketName);
        }
        
        if (isInvalidConfig(datastoreId)) {
            log.error("❌ Vertex AI Search Datastore ID is not configured! Please set gcp.discovery-engine.datastore-id in application.yml or via environment variable GCP_DISCOVERY_ENGINE_DATASTORE_ID");
            hasErrors = true;
        } else {
            log.info("✓ Vertex AI Search Datastore ID: {}", datastoreId);
        }
        
        if (hasErrors) {
            log.error("========================================");
            log.error("⚠️  CONFIGURATION ERRORS DETECTED!");
            log.error("The application will not work correctly until you configure the missing values.");
            log.error("Please refer to the README.md for setup instructions:");
            log.error("https://github.com/gajananchalke9/smart-job-hunt/blob/main/README.md");
            log.error("========================================");
        } else {
            log.info("✓ All required GCP configuration values are set");
            log.info("Note: This validation only checks that values are configured, not that they are valid or that GCP resources exist.");
        }
    }

    /**
     * Checks if a configuration value is invalid (null, blank, or starts with "YOUR_").
     *
     * @param value the configuration value to check
     * @return true if the value is invalid, false otherwise
     */
    private boolean isInvalidConfig(String value) {
        return value == null || value.isBlank() || value.startsWith("YOUR_");
    }
}
