package com.smartjobhunt.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Gemini-generated explainable match result for one job against a candidate resume.
 *
 * <p>Gemini is prompted to return strict JSON that maps to this class.
 */
@Schema(description = "AI-generated match result with explainable scoring for a job against a resume")
public class MatchResult {

    @Schema(description = "Vertex AI Search document ID of the matched job", 
            example = "gs://smart-job-hunt-bucket/jobs/senior-java-dev.pdf")
    /** Vertex AI Search document ID of the matched job. */
    private String documentId;

    @Schema(description = "Job title extracted from metadata or PDF content", 
            example = "Senior Java Developer")
    /** Job title extracted from metadata or PDF content. */
    private String title;

    @Schema(description = "GCS URI of the job description PDF", 
            example = "gs://smart-job-hunt-bucket/jobs/senior-java-dev.pdf")
    /** GCS URI of the job description PDF. */
    private String gcsUri;

    @Schema(description = "AI-assigned match score from 0 to 100", 
            example = "85", minimum = "0", maximum = "100")
    /** Gemini-assigned match score (0 – 100). */
    private int score;

    @Schema(description = "List of candidate strengths for this job", 
            example = "[\"Strong Java and Spring Boot experience\", \"5+ years of backend development\"]")
    /** List of candidate strengths for this job. */
    private List<String> strengths;

    @Schema(description = "List of skill or experience gaps", 
            example = "[\"Limited cloud experience\", \"No Kubernetes expertise\"]")
    /** List of skill or experience gaps. */
    private List<String> gaps;

    @Schema(description = "One-line human-readable summary of the match", 
            example = "Excellent match with strong technical skills but limited cloud exposure")
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
