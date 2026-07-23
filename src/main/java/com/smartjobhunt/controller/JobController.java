package com.smartjobhunt.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartjobhunt.dto.JobMetadata;
import com.smartjobhunt.dto.JobSearchRequest;
import com.smartjobhunt.dto.JobSearchResult;
import com.smartjobhunt.dto.JobUploadResponse;
import com.smartjobhunt.service.GcsService;
import com.smartjobhunt.service.VertexSearchService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST controller for job-profile management.
 *
 * <ul>
 *   <li>{@code POST /api/jobs/upload} — upload a job description PDF to GCS and
 *       import it into the Vertex AI Search datastore</li>
 *   <li>{@code POST /api/jobs/search} — natural-language search over indexed job profiles</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final GcsService gcsService;
    private final VertexSearchService vertexSearchService;
    private final ObjectMapper objectMapper;

    public JobController(GcsService gcsService, VertexSearchService vertexSearchService) {
        this.gcsService = gcsService;
        this.vertexSearchService = vertexSearchService;
        this.objectMapper = new ObjectMapper();
    }

    // ─────────────────────────────────────────────────────────────
    // POST /api/jobs/upload
    // ─────────────────────────────────────────────────────────────

    /**
     * Accepts a job-description PDF with structured metadata, uploads both the PDF and
     * metadata JSONL to GCS, and imports the metadata into the Vertex AI Search datastore.
     *
     * @param file     the PDF file (multipart/form-data, field name {@code file})
     * @param metadata JSON string containing structured metadata (title, job_id, company, etc.)
     *                 Optional - if not provided, metadata will be extracted from filename
     * @return {@link JobUploadResponse} containing the document ID and GCS URIs
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobUploadResponse> uploadJob(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "metadata", required = false) String metadata) throws Exception {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        if (!isPdf(file)) {
            throw new IllegalArgumentException("Only PDF files are accepted.");
        }

        // Parse metadata from JSON string or create default metadata
        JobMetadata jobMetadata = parseOrCreateMetadata(metadata, file.getOriginalFilename());

        // 1) Upload PDF and create JSONL metadata to GCS
        String jsonlGcsUri = gcsService.uploadJobWithMetadata(file, jobMetadata);

        // 2) Import JSONL metadata into Vertex AI Search (async LRO – waits for completion)
        vertexSearchService.importDocument(jsonlGcsUri);

        // Extract document ID from the JSONL GCS URI (filename without extension)
        String documentId = extractDocumentIdFromUri(jsonlGcsUri);

        return ResponseEntity.ok(new JobUploadResponse(
                documentId,
                jsonlGcsUri,
                "Job uploaded and indexed successfully with metadata."));
    }

    /**
     * Parses metadata JSON string or creates default metadata from filename.
     */
    private JobMetadata parseOrCreateMetadata(String metadataJson, String filename) throws Exception {
        if (metadataJson != null && !metadataJson.isBlank()) {
            return objectMapper.readValue(metadataJson, JobMetadata.class);
        }

        // Create default metadata from filename
        JobMetadata defaultMetadata = new JobMetadata();
        String title = (filename != null && !filename.isBlank())
                ? filename.replace(".pdf", "").replace("_", " ").replace("-", " ")
                : "Untitled Job";
        defaultMetadata.setTitle(title);
        defaultMetadata.setJobId("N/A");
        defaultMetadata.setCompany("N/A");
        defaultMetadata.setLocations(List.of());
        return defaultMetadata;
    }

    /**
     * Extracts a document ID from the JSONL GCS URI.
     * For example: gs://bucket/jobs/foo.jsonl -> foo
     */
    private String extractDocumentIdFromUri(String gcsUri) {
        if (gcsUri == null || gcsUri.isEmpty()) {
            return "unknown";
        }
        String filename = gcsUri;
        int lastSlash = gcsUri.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < gcsUri.length() - 1) {
            filename = gcsUri.substring(lastSlash + 1);
        }
        if (filename.toLowerCase().endsWith(".jsonl")) {
            filename = filename.substring(0, filename.length() - 6);
        }
        return filename;
    }

    // ─────────────────────────────────────────────────────────────
    // POST /api/jobs/search
    // ─────────────────────────────────────────────────────────────

    /**
     * Searches the Vertex AI Search datastore with a natural-language query.
     *
     * @param request JSON body containing the {@code query} string and optional {@code pageSize}
     * @return ranked list of matching job profiles
     */
    @PostMapping("/search")
    public ResponseEntity<List<JobSearchResult>> searchJobs(
            @Valid @RequestBody JobSearchRequest request) {

        List<JobSearchResult> results =
                vertexSearchService.search(request.getQuery(), request.getPageSize());

        return ResponseEntity.ok(results);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private boolean isPdf(MultipartFile file) {
        String contentType = file.getContentType();
        String filename    = file.getOriginalFilename();
        return "application/pdf".equalsIgnoreCase(contentType)
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }
}
