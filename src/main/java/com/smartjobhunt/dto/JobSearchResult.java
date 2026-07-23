package com.smartjobhunt.dto;

/**
 * Represents a single job profile document returned by a Vertex AI Search query.
 */
public class JobSearchResult {

    /** Vertex AI Search document ID. */
    private String documentId;

    /** Job title extracted from the document metadata or PDF content. */
    private String title;

    /** Short snippet / summary from the search result. */
    private String snippet;

    /** GCS URI of the original PDF, e.g. {@code gs://bucket/jobs/foo.pdf}. */
    private String gcsUri;

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
