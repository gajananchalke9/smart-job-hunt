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
import java.util.regex.Pattern;

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
    
    /** Compiled pattern for detecting UUID-based filenames (8-4-4-4-12 hex digits). */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final SearchServiceClient searchServiceClient;
    private final DocumentServiceClient documentServiceClient;
    private final String projectId;
    private final String location;
    private final String datastoreId;
    private final String errorBucket;

    public VertexSearchService(
            SearchServiceClient searchServiceClient,
            DocumentServiceClient documentServiceClient,
            @Value("${gcp.project-id}") String projectId,
            @Value("${gcp.discovery-engine-location:global}") String location,
            @Value("${gcp.discovery-engine.datastore-id}") String datastoreId,
            @Value("${gcp.gcs.bucket}") String errorBucket) {
        this.searchServiceClient = searchServiceClient;
        this.documentServiceClient = documentServiceClient;
        this.projectId = projectId;
        this.location = location;
        this.datastoreId = datastoreId;
        this.errorBucket = errorBucket;
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
     * <p>Error logs from the import process are stored in the configured GCS bucket under the "log/" directory.
     *
     * @param jsonlGcsUri the GCS URI of the JSONL metadata file, e.g. {@code gs://bucket/Job-profiles/foo.jsonl}
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws ExecutionException   if the import operation fails
     */
    public void importDocument(String jsonlGcsUri)
            throws InterruptedException, ExecutionException {

        log.info("Starting document import to Vertex AI Search - jsonlUri: {}", jsonlGcsUri);

        // Build the branch resource name where documents are stored
        String branchName = BranchName.of(projectId, location, datastoreId, "default_branch").toString();
        log.debug("Using branch name: {}", branchName);

        // GCS source pointing at the JSONL metadata file
        GcsSource gcsSource = GcsSource.newBuilder()
                .addInputUris(jsonlGcsUri)
                .setDataSchema("custom")   // custom schema for structured JSONL data
                .build();

        // Configure error output to go to the specified bucket under log/ directory
        String errorGcsUri = "gs://" + errorBucket + "/log/";
        log.debug("Configuring error output to: {}", errorGcsUri);

        ImportDocumentsRequest request = ImportDocumentsRequest.newBuilder()
                .setParent(branchName)
                .setGcsSource(gcsSource)
                .setReconciliationMode(ImportDocumentsRequest.ReconciliationMode.INCREMENTAL)
                .setErrorConfig(ImportDocumentsRequest.ErrorConfig.newBuilder()
                        .setGcsPrefix(errorGcsUri)
                        .build())
                .build();

        log.debug("Submitting import documents request - mode: INCREMENTAL, errorGcsUri: {}", errorGcsUri);
        // Block until the LRO finishes
        ImportDocumentsResponse response =
                documentServiceClient.importDocumentsAsync(request).get();

        log.info("Document import completed successfully - jsonlUri: {}", jsonlGcsUri);

        // Log any per-document errors (non-fatal – the overall import still succeeded)
        if (response.getErrorSamplesCount() > 0) {
            log.warn("Import completed with {} error samples. Error logs stored at: {}", 
                    response.getErrorSamplesCount(), errorGcsUri);
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
        log.info("Starting Vertex AI Search - query: '{}', pageSize: {}", query, pageSize);
        
        String servingConfig = ServingConfigName.of(
                projectId, location, datastoreId, "default_config").toString();
        log.debug("Using serving config: {}", servingConfig);

        SearchRequest request = SearchRequest.newBuilder()
                .setServingConfig(servingConfig)
                .setQuery(query)
                .setPageSize(pageSize)
                .build();

        List<JobSearchResult> results = new ArrayList<>();
        int resultCount = 0;

        for (SearchResponse.SearchResult result :
                searchServiceClient.search(request).iterateAll()) {

            com.google.cloud.discoveryengine.v1.Document doc = result.getDocument();
            // Use fully-qualified com.google.protobuf.Value to avoid naming clash
            Map<String, com.google.protobuf.Value> fields = doc.getStructData().getFieldsMap();

            // Extract GCS URI from content field or structData
            String gcsUri = null;
            if (doc.hasContent() && doc.getContent().hasUri()) {
                gcsUri = doc.getContent().getUri();
                log.debug("Extracted URI from content field: {}", gcsUri);
            } else {
                // Fallback: try to find URI in structData fields
                gcsUri = extractStringField(fields, "uri",
                        extractStringField(fields, "gcs_uri", doc.getName()));
                log.debug("Extracted URI from structData or doc.getName(): {}", gcsUri);
            }
            
            // Extract title with multiple fallback strategies
            // 1. Try structured metadata (title field)
            String title = extractStringField(fields, "title", null);
            
            // 2. If no title or title equals documentId, try name field
            if (title == null || title.isEmpty() || title.equals(doc.getId())) {
                title = extractStringField(fields, "name", null);
            }
            
            // 3. If still no title or title equals documentId, try extracting from GCS URI
            if (title == null || title.isEmpty() || title.equals(doc.getId())) {
                log.debug("No valid title in metadata for doc {}, extracting from GCS URI", doc.getId());
                title = extractTitleFromGcsUri(gcsUri);
            }
            
            // 4. Final fallback to "Untitled Job" if all else fails
            if (title == null || title.isEmpty()) {
                log.warn("Could not extract title for doc {}, using 'Untitled Job'", doc.getId());
                title = "Untitled Job";
            }
            
            String snippet = extractSnippet(result);

            results.add(new JobSearchResult(doc.getId(), title, snippet, gcsUri, 0.0));
            resultCount++;
            
            log.debug("Search result {}: documentId={}, title='{}', gcsUri='{}'", 
                    resultCount, doc.getId(), title, gcsUri);
        }

        log.info("Vertex AI Search completed - returned {} results for query: '{}'", results.size(), query);
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
     * (without extension), replacing underscores/hyphens with spaces, and trimming.
     *
     * <p>This is useful for documents that were uploaded without structured metadata
     * or when the title field is missing from the search results.
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

        // Remove file extension
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            filename = filename.substring(0, lastDot);
        }

        // Check if filename looks like a UUID (no meaningful title)
        // UUID pattern: 8-4-4-4-12 hex digits with hyphens
        if (UUID_PATTERN.matcher(filename).matches()) {
            log.debug("Filename is a UUID, cannot extract meaningful title: {}", filename);
            return "Untitled Job";
        }

        // Replace underscores and hyphens with spaces for better readability
        // Also replace multiple spaces with single space
        String title = filename.replaceAll("[_-]", " ")
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
