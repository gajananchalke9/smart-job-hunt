package com.smartjobhunt.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Represents a single job profile document returned by a Vertex AI Search query.
 */
@Schema(description = "Job search result from Vertex AI Search")
public class JobSearchResult {

    @Schema(description = "Vertex AI Search document ID", 
            example = "gs://smart-job-hunt-bucket/jobs/backend-engineer.pdf")
    /** Vertex AI Search document ID. */
    private String documentId;

    @Schema(description = "Job title extracted from document metadata or filename", 
            example = "Backend Engineer - Remote")
    /** Job title extracted from the document metadata or filename. */
    private String title;

    @Schema(description = "Short snippet/summary from the search result", 
            example = "We are looking for an experienced Backend Engineer with expertise in Java and microservices...")
    /** Short snippet / summary from the search result. */
    private String snippet;

    @Schema(description = "GCS URI of the original PDF", 
            example = "gs://smart-job-hunt-bucket/jobs/backend-engineer.pdf")
    /** GCS URI of the original PDF, e.g. {@code gs://bucket/jobs/foo.pdf}. */
    private String gcsUri;

    @Schema(description = "Relevance score from Vertex AI Search (higher = more relevant)", 
            example = "0.92")
    /** Relevance score assigned by Vertex AI Search (higher = more relevant). */
    private double relevanceScore;

    // ── Constructors ──────────────────────────────────────────────

    public JobSearchResult() {}

    public JobSearchResult(String documentId, String title, String snippet,
                           String gcsUri, double relevanceScore) {
        this.documentId = documentId;
        this.title = title;
        this.snippet = snippet;
        this.gcsUri = gcsUri;
        this.relevanceScore = relevanceScore;
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public String getGcsUri() { return gcsUri; }
    public void setGcsUri(String gcsUri) { this.gcsUri = gcsUri; }

    public double getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(double relevanceScore) { this.relevanceScore = relevanceScore; }
}
