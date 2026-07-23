package com.smartjobhunt.controller;

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
import java.util.UUID;

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
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobUploadResponse> uploadJob(
            @RequestParam("file") MultipartFile file) throws Exception {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        if (!isPdf(file)) {
            throw new IllegalArgumentException("Only PDF files are accepted.");
        }

        // 1) Upload to GCS
        String gcsUri = gcsService.uploadPdf(file, null);

        // 2) Assign a stable document ID (UUID)
        String documentId = UUID.randomUUID().toString();

        // 3) Import into Vertex AI Search (async LRO – waits for completion)
        vertexSearchService.importDocument(gcsUri, documentId);

        return ResponseEntity.ok(new JobUploadResponse(
                documentId,
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
