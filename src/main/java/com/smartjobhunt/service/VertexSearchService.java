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
     * Imports a single PDF from GCS into the Vertex AI Search datastore.
     *
     * <p>The import is triggered asynchronously; this method waits for the
     * long-running operation to complete before returning.
     *
     * @param gcsUri     the GCS URI of the PDF, e.g. {@code gs://bucket/jobs/foo.pdf}
     * @param documentId a stable ID for this document (used as the Vertex AI Search document ID)
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws ExecutionException   if the import operation fails
     */
    public void importDocument(String gcsUri, String documentId)
            throws InterruptedException, ExecutionException {

        // Build the branch resource name where documents are stored
        String branchName = BranchName.of(projectId, location, datastoreId, "default_branch").toString();

        // GCS source pointing at the specific PDF
        GcsSource gcsSource = GcsSource.newBuilder()
                .addInputUris(gcsUri)
                .setDataSchema("content")   // unstructured / content schema for PDFs
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
                    System.err.printf("[VertexSearchService] import warning for %s: %s%n",
                            gcsUri, e.getMessage()));
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

            String title   = extractStringField(fields, "title",
                    extractStringField(fields, "name", doc.getId()));
            String snippet = extractSnippet(result);
            String gcsUri  = extractStringField(fields, "uri",
                    extractStringField(fields, "gcs_uri", ""));

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
