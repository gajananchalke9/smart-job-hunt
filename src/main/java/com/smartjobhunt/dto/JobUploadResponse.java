package com.smartjobhunt.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code POST /api/jobs/upload}.
 */
@Schema(description = "Response object after successfully uploading a job description")
public class JobUploadResponse {

    @Schema(description = "Vertex AI Search document ID assigned to this job", 
            example = "gs://smart-job-hunt-bucket/jobs/software-engineer-123.pdf")
    /** Vertex AI Search document ID assigned to this job. */
    private String documentId;

    @Schema(description = "GCS URI where the PDF was stored", 
            example = "gs://smart-job-hunt-bucket/jobs/software-engineer-123.pdf")
    /** GCS URI where the PDF was stored, e.g. {@code gs://bucket/jobs/abc.pdf}. */
    private String gcsUri;

    @Schema(description = "Human-readable status message", 
            example = "Job uploaded and indexed successfully.")
    /** Human-readable status message. */
    private String message;

    // ── Constructors ──────────────────────────────────────────────

    public JobUploadResponse() {}

    public JobUploadResponse(String documentId, String gcsUri, String message) {
        this.documentId = documentId;
        this.gcsUri = gcsUri;
        this.message = message;
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getGcsUri() { return gcsUri; }
    public void setGcsUri(String gcsUri) { this.gcsUri = gcsUri; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
