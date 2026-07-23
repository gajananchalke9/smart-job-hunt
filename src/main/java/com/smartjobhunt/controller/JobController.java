package com.smartjobhunt.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartjobhunt.dto.JobMetadata;
import com.smartjobhunt.dto.JobSearchRequest;
import com.smartjobhunt.dto.JobSearchResult;
import com.smartjobhunt.dto.JobUploadResponse;
import com.smartjobhunt.service.GcsService;
import com.smartjobhunt.service.JobMetadataExtractionService;
import com.smartjobhunt.service.VertexSearchService;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Job Management", description = "APIs for uploading and searching job descriptions")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final GcsService gcsService;
    private final VertexSearchService vertexSearchService;
    private final JobMetadataExtractionService metadataExtractionService;
    private final ObjectMapper objectMapper;

    public JobController(GcsService gcsService,
                        VertexSearchService vertexSearchService,
                        JobMetadataExtractionService metadataExtractionService,
                        ObjectMapper objectMapper) {
        this.gcsService = gcsService;
        this.vertexSearchService = vertexSearchService;
        this.metadataExtractionService = metadataExtractionService;
        this.objectMapper = objectMapper;
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
     *                 Optional - if not provided, metadata will be extracted from PDF content using AI
     * @return {@link JobUploadResponse} containing the document ID and GCS URIs
     */
    @Operation(
            summary = "Upload job description PDF",
            description = "Uploads a job description PDF to Google Cloud Storage and indexes it in Vertex AI Search for searchability. "
                    + "The PDF must contain selectable text (not scanned images)."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Job uploaded and indexed successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = JobUploadResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid input - file is empty or not a PDF"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during upload or indexing"
            )
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobUploadResponse> uploadJob(
            @Parameter(description = "Job description PDF file", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Optional JSON metadata string. If not provided, metadata will be extracted from PDF content using AI.", required = false)
            @RequestParam(value = "metadata", required = false) String metadata) throws Exception {

        log.info("Received job upload request - filename: {}, size: {} bytes, metadata provided: {}",
                file.getOriginalFilename(), file.getSize(), metadata != null);

        if (file.isEmpty()) {
            log.error("Job upload failed - file is empty");
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        if (!isPdf(file)) {
            log.error("Job upload failed - file is not a PDF: {}", file.getContentType());
            throw new IllegalArgumentException("Only PDF files are accepted.");
        }

        // Parse metadata from JSON string or extract from PDF content
        log.debug("Parsing or extracting job metadata");
        JobMetadata jobMetadata = parseOrCreateMetadata(metadata, file);
        log.info("Job metadata prepared - title: {}, company: {}, jobId: {}",
                jobMetadata.getTitle(), jobMetadata.getCompany(), jobMetadata.getJobId());

        // 1) Upload PDF and create JSONL metadata to GCS
        log.debug("Uploading job PDF and metadata to GCS");
        GcsService.UploadResult result = gcsService.uploadJobWithMetadata(file, jobMetadata);
        log.info("Job uploaded to GCS - documentId: {}, jsonlUri: {}",
                result.getDocumentId(), result.getJsonlGcsUri());

        // 2) Import JSONL metadata into Vertex AI Search (async LRO – waits for completion)
        log.debug("Importing document into Vertex AI Search - documentId: {}", result.getDocumentId());
        vertexSearchService.importDocument(result.getJsonlGcsUri());
        log.info("Job successfully indexed in Vertex AI Search - documentId: {}", result.getDocumentId());

        return ResponseEntity.ok(new JobUploadResponse(
                result.getDocumentId(),
                result.getJsonlGcsUri(),
                "Job uploaded and indexed successfully with metadata."));
    }

    /**
     * Parses metadata JSON string or extracts metadata from PDF content using AI.
     */
    private JobMetadata parseOrCreateMetadata(String metadataJson, MultipartFile file) throws Exception {
        if (metadataJson != null && !metadataJson.isBlank()) {
            log.debug("Parsing provided metadata JSON");
            try {
                JobMetadata parsed = objectMapper.readValue(metadataJson, JobMetadata.class);
                log.debug("Successfully parsed metadata JSON");
                return parsed;
            } catch (Exception e) {
                log.error("Failed to parse metadata JSON: {}", e.getMessage());
                throw new IllegalArgumentException(
                    "Invalid metadata JSON format: " + e.getMessage(), e);
            }
        }

        // Extract metadata from PDF content using AI
        log.info("No metadata provided - extracting from PDF content using AI");
        return metadataExtractionService.extractMetadata(file);
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
    @Operation(
            summary = "Search job descriptions",
            description = "Performs a natural-language search across all indexed job descriptions using Vertex AI Search. "
                    + "Returns ranked results based on semantic similarity to the search query."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Search completed successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = JobSearchResult.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid search request"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during search"
            )
    })
    @PostMapping("/search")
    public ResponseEntity<List<JobSearchResult>> searchJobs(
            @Parameter(description = "Search request containing query and optional page size", required = true)
            @Valid @RequestBody JobSearchRequest request) {

        log.info("Received job search request - query: '{}', pageSize: {}",
                request.getQuery(), request.getPageSize());

        List<JobSearchResult> results =
                vertexSearchService.search(request.getQuery(), request.getPageSize());

        log.info("Job search completed - found {} results for query: '{}'",
                results.size(), request.getQuery());

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
