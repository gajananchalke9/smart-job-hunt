package com.smartjobhunt.config;

import com.google.cloud.discoveryengine.v1.DocumentServiceClient;
import com.google.cloud.discoveryengine.v1.DocumentServiceSettings;
import com.google.cloud.discoveryengine.v1.SearchServiceClient;
import com.google.cloud.discoveryengine.v1.SearchServiceSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.vertexai.VertexAI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Spring configuration that creates GCP client beans as singletons.
 *
 * <p>All clients are initialised with <em>Application Default Credentials (ADC)</em>.
 * Set the {@code GOOGLE_APPLICATION_CREDENTIALS} environment variable to the path of a
 * service-account key file, or run {@code gcloud auth application-default login} in
 * development environments.
 */
@Configuration
public class GcpConfig {

    @Value("${gcp.project-id}")
    private String projectId;

    @Value("${gcp.location}")
    private String vertexAiLocation;

    @Value("${gcp.discovery-engine-location:global}")
    private String discoveryEngineLocation;

    // ─────────────────────────────────────────────────────────────
    // Cloud Storage
    // ─────────────────────────────────────────────────────────────

    /**
     * Google Cloud Storage client.
     * Uses ADC for authentication and the configured project ID.
     */
    @Bean
    public Storage storage() {
        return StorageOptions.newBuilder()
                .setProjectId(projectId)
                .build()
                .getService();
    }

    // ─────────────────────────────────────────────────────────────
    // Vertex AI Search (Discovery Engine)
    // ─────────────────────────────────────────────────────────────

    /**
     * Discovery Engine {@link SearchServiceClient} for running search queries
     * against the Vertex AI Search datastore.
     */
    @Bean
    public SearchServiceClient searchServiceClient() throws IOException {
        String endpoint = buildDiscoveryEngineEndpoint();
        SearchServiceSettings settings = SearchServiceSettings.newBuilder()
                .setEndpoint(endpoint)
                .build();
        return SearchServiceClient.create(settings);
    }

    /**
     * Discovery Engine {@link DocumentServiceClient} for importing documents
     * (job-description PDFs) into the Vertex AI Search datastore.
     */
    @Bean
    public DocumentServiceClient documentServiceClient() throws IOException {
        String endpoint = buildDiscoveryEngineEndpoint();
        DocumentServiceSettings settings = DocumentServiceSettings.newBuilder()
                .setEndpoint(endpoint)
                .build();
        return DocumentServiceClient.create(settings);
    }

    // ─────────────────────────────────────────────────────────────
    // Vertex AI (Gemini)
    // ─────────────────────────────────────────────────────────────

    /**
     * Vertex AI client used by {@link com.smartjobhunt.service.GeminiScoringService}
     * to call the Gemini generative model.
     */
    @Bean
    public VertexAI vertexAI() {
        return new VertexAI(projectId, vertexAiLocation);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String buildDiscoveryEngineEndpoint() {
        // For "global" the endpoint is discoveryengine.googleapis.com:443
        // For regional deployments it is {location}-discoveryengine.googleapis.com:443
        if ("global".equalsIgnoreCase(discoveryEngineLocation)) {
            return "discoveryengine.googleapis.com:443";
        }
        return discoveryEngineLocation + "-discoveryengine.googleapis.com:443";
    }
}
