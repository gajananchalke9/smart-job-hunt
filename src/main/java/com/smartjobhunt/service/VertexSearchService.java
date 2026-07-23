package com.smartjobhunt.service;

import com.google.cloud.discoveryengine.v1.BranchName;
import com.google.cloud.discoveryengine.v1.DocumentServiceClient;
import com.google.cloud.discoveryengine.v1.GcsSource;
import com.google.cloud.discoveryengine.v1.ImportDocumentsRequest;
import com.google.cloud.discoveryengine.v1.ImportDocumentsResponse;
import com.google.cloud.discoveryengine.v1.SearchRequest;
import com.google.cloud.discoveryengine.v1.SearchResponse;
import com.google.cloud.discoveryengine.v1.SearchServiceClient;
import com.google.cloud.discoveryengine.v1.ServingConfigName;
import com.smartjobhunt.dto.JobSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Wraps calls to Vertex AI Search (Discovery Engine).
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Import a GCS-hosted PDF into the datastore via
 *       {@link DocumentServiceClient#importDocumentsAsync}</li>
 *   <li>Search the datastore with a natural-language query via
 *       {@link SearchServiceClient#search}</li>
 * </ol>
 */
@Service
public class VertexSearchService {

    private static final Logger log = LoggerFactory.getLogger(VertexSearchService.class);

    private final SearchServiceClient searchServiceClient;
    private final DocumentServiceClient documentServiceClient;
    private final String projectId;
    private final String location;
    private final String datastoreId;

    public VertexSearchService(
            SearchServiceClient searchServiceClient,
            DocumentServiceClient documentServiceClient,
            @Value("${gcp.project-id}") String projectId,
            @Value("${gcp.discovery-engine-location:global}") String location,
            @Value("${gcp.discovery-engine.datastore-id}") String datastoreId) {
        this.searchServiceClient = searchServiceClient;
        this.documentServiceClient = documentServiceClient;
        this.projectId = projectId;
        this.location = location;
        this.datastoreId = datastoreId;
    }

    // ─────────────────────────────────────────────────────────────
    // Document import
    // ─────────────────────────────────────────────────────────────

    /**
     * Imports a JSONL file with structured data from GCS into the Vertex AI Search datastore.
     *
     * <p>The import is triggered asynchronously; this method waits for the
     * long-running operation to complete before returning.
     *
     * <p>The JSONL file should contain structured metadata including title, job_id, company, etc.,
     * as well as a content reference to the PDF file.
     *
     * @param jsonlGcsUri the GCS URI of the JSONL metadata file, e.g. {@code gs://bucket/jobs/foo.jsonl}
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws ExecutionException   if the import operation fails
     */
    public void importDocument(String jsonlGcsUri)
            throws InterruptedException, ExecutionException {

        // Build the branch resource name where documents are stored
        String branchName = BranchName.of(projectId, location, datastoreId, "default_branch").toString();

        // GCS source pointing at the JSONL metadata file
        GcsSource gcsSource = GcsSource.newBuilder()
                .addInputUris(jsonlGcsUri)
                .setDataSchema("custom")   // custom schema for structured JSONL data
                .build();

        ImportDocumentsRequest request = ImportDocumentsRequest.newBuilder()
                .setParent(branchName)
                .setGcsSource(gcsSource)
                .setReconciliationMode(ImportDocumentsRequest.ReconciliationMode.INCREMENTAL)
                .build();

        // Block until the LRO finishes
        ImportDocumentsResponse response =
                documentServiceClient.importDocumentsAsync(request).get();

        // Log any per-document errors (non-fatal – the overall import still succeeded)
        if (response.getErrorSamplesCount() > 0) {
            response.getErrorSamplesList().forEach(e ->
                    log.warn("[VertexSearchService] import warning for {}: {}", jsonlGcsUri, e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────

    /**
     * Searches the Vertex AI Search datastore with a natural-language query.
     *
     * @param query    the natural-language search string
     * @param pageSize maximum number of results to return
     * @return list of {@link JobSearchResult} sorted by Vertex AI Search relevance
     */
    public List<JobSearchResult> search(String query, int pageSize) {
        String servingConfig = ServingConfigName.of(
                projectId, location, datastoreId, "default_config").toString();

        SearchRequest request = SearchRequest.newBuilder()
                .setServingConfig(servingConfig)
                .setQuery(query)
                .setPageSize(pageSize)
                .build();

        List<JobSearchResult> results = new ArrayList<>();

        for (SearchResponse.SearchResult result :
                searchServiceClient.search(request).iterateAll()) {

            com.google.cloud.discoveryengine.v1.Document doc = result.getDocument();
            // Use fully-qualified com.google.protobuf.Value to avoid naming clash
            Map<String, com.google.protobuf.Value> fields = doc.getStructData().getFieldsMap();

            // Extract GCS URI first - needed for both gcsUri field and title fallback
            // Try fields in order: "uri" -> "gcs_uri" -> doc.getName()
            String gcsUri  = extractStringField(fields, "uri",
                    extractStringField(fields, "gcs_uri", doc.getName()));
            
            // Try to get title from structured metadata, fall back to extracting from GCS URI
            String title   = extractStringField(fields, "title",
                    extractStringField(fields, "name", null));
            if (title == null || title.isEmpty() || title.equals(doc.getId())) {
                title = extractTitleFromGcsUri(gcsUri);
            }
            
            String snippet = extractSnippet(result);

            results.add(new JobSearchResult(doc.getId(), title, snippet, gcsUri, 0.0));
        }

        return results;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String extractStringField(Map<String, com.google.protobuf.Value> fields,
                                      String key, String defaultValue) {
        com.google.protobuf.Value v = fields.get(key);
        if (v != null && v.hasStringValue()) {
            return v.getStringValue();
        }
        return defaultValue;
    }

    /**
     * Extracts a human-readable title from a GCS URI by taking the filename
     * (without extension) and replacing underscores with spaces.
     *
     * @param gcsUri the GCS URI, e.g. {@code gs://bucket/jobs/Lead_Java_Backend_Engineer.pdf}
     * @return a human-readable title, or "Untitled Job" if extraction fails
     */
    private String extractTitleFromGcsUri(String gcsUri) {
        if (gcsUri == null || gcsUri.isEmpty()) {
            return "Untitled Job";
        }

        // Extract filename from GCS URI (e.g., "gs://bucket/jobs/Lead_Java_Backend_Engineer.pdf")
        String filename = gcsUri;
        int lastSlash = gcsUri.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < gcsUri.length() - 1) {
            filename = gcsUri.substring(lastSlash + 1);
        }

        // Remove .pdf extension (currently only PDFs are supported for job uploads)
        if (filename.toLowerCase().endsWith(".pdf")) {
            filename = filename.substring(0, filename.length() - 4);
        }

        // Replace underscores with spaces for better readability
        // Also replace multiple spaces with single space
        String title = filename.replaceAll("_", " ")
                               .replaceAll("\\s+", " ")
                               .trim();

        return title.isEmpty() ? "Untitled Job" : title;
    }

    private String extractSnippet(SearchResponse.SearchResult result) {
        com.google.cloud.discoveryengine.v1.Document doc = result.getDocument();
        Map<String, com.google.protobuf.Value> fields = doc.getStructData().getFieldsMap();

        // Try structured metadata fields first
        String snippet = extractStringField(fields, "snippet",
                extractStringField(fields, "description", ""));
        if (!snippet.isBlank()) {
            return snippet;
        }

        // Fall back to derived struct content (Vertex AI Search snippet extraction)
        if (doc.hasDerivedStructData()) {
            Map<String, com.google.protobuf.Value> derived =
                    doc.getDerivedStructData().getFieldsMap();
            com.google.protobuf.Value snippetVal = derived.get("snippets");
            if (snippetVal != null && snippetVal.hasListValue()
                    && !snippetVal.getListValue().getValuesList().isEmpty()) {
                com.google.protobuf.Value first =
                        snippetVal.getListValue().getValuesList().get(0);
                if (first.hasStructValue()) {
                    com.google.protobuf.Value snippetText =
                            first.getStructValue().getFieldsMap().get("snippet");
                    if (snippetText != null && snippetText.hasStringValue()) {
                        return snippetText.getStringValue();
                    }
                }
            }
        }
        return "";
    }
}
