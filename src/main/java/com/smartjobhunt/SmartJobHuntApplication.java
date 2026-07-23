package com.smartjobhunt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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

    public static void main(String[] args) {
        SpringApplication.run(SmartJobHuntApplication.class, args);
    }
}
