package com.smartjobhunt.dto;

import java.util.List;

/**
 * Gemini-generated explainable match result for one job against a candidate resume.
 *
 * <p>Gemini is prompted to return strict JSON that maps to this class.
 */
public class MatchResult {

    /** Vertex AI Search document ID of the matched job. */
    private String documentId;

    /** Job title (from metadata or filename). */
    private String title;

    /** GCS URI of the job description PDF. */
    private String gcsUri;

    /** Gemini-assigned match score (0 – 100). */
    private int score;

    /** List of candidate strengths for this job. */
    private List<String> strengths;

    /** List of skill or experience gaps. */
    private List<String> gaps;

    /** One-line human-readable summary of the match. */
    private String summary;

    // ── Constructors ──────────────────────────────────────────────

    public MatchResult() {}

    // ── Getters & Setters ─────────────────────────────────────────

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getGcsUri() { return gcsUri; }
    public void setGcsUri(String gcsUri) { this.gcsUri = gcsUri; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public List<String> getStrengths() { return strengths; }
    public void setStrengths(List<String> strengths) { this.strengths = strengths; }

    public List<String> getGaps() { return gaps; }
    public void setGaps(List<String> gaps) { this.gaps = gaps; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
