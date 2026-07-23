package com.smartjobhunt.controller;

import com.smartjobhunt.dto.JobSearchRequest;
import com.smartjobhunt.dto.JobSearchResult;
import com.smartjobhunt.dto.JobUploadResponse;
import com.smartjobhunt.service.GcsService;
import com.smartjobhunt.service.VertexSearchService;
import io.swagger.v3.oas.annotations.Operation;
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

    private final GcsService gcsService;
    private final VertexSearchService vertexSearchService;

    public JobController(GcsService gcsService, VertexSearchService vertexSearchService) {
        this.gcsService = gcsService;
        this.vertexSearchService = vertexSearchService;
    }

    // ─────────────────────────────────────────────────────────────
    // POST /api/jobs/upload
    // ─────────────────────────────────────────────────────────────

    /**
     * Accepts a job-description PDF, uploads it to GCS, and imports it into the
     * Vertex AI Search datastore for indexing.
     *
     * @param file the PDF file (multipart/form-data, field name {@code file})
     * @return {@link JobUploadResponse} containing the document ID and GCS URI
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
            @RequestParam("file") MultipartFile file) throws Exception {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        if (!isPdf(file)) {
            throw new IllegalArgumentException("Only PDF files are accepted.");
        }

        // 1) Upload to GCS
        String gcsUri = gcsService.uploadPdf(file, null);

        // 2) Import into Vertex AI Search (async LRO – waits for completion)
        vertexSearchService.importDocument(gcsUri);

        return ResponseEntity.ok(new JobUploadResponse(
                gcsUri,   // use GCS URI as the stable identifier exposed to callers
                gcsUri,
                "Job uploaded and indexed successfully."));
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
