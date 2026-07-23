package com.smartjobhunt.dto;

/**
 * Response body for {@code POST /api/jobs/upload}.
 */
public class JobUploadResponse {

    /** Vertex AI Search document ID assigned to this job. */
    private String documentId;

    /** GCS URI where the PDF was stored, e.g. {@code gs://bucket/jobs/abc.pdf}. */
    private String gcsUri;

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
