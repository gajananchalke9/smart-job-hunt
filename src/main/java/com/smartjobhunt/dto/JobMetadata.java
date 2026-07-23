package com.smartjobhunt.dto;

import java.util.List;

/**
 * Represents structured metadata for a job description.
 * This data will be used to create a JSONL file for Vertex AI Search indexing.
 *
 * <p>Note: The {@code title}, {@code jobId}, and {@code company} fields should typically
 * be provided for proper search functionality. If not provided explicitly, they will be
 * extracted from the PDF content using AI. The system does not enforce uniqueness of
 * {@code jobId} at the API level - callers are responsible for ensuring unique job identifiers.
 */
public class JobMetadata {

    /** Unique identifier for the job (e.g., "R0357981"). Should be unique per job posting. */
    private String jobId;

    /** Job title (e.g., "Apprentice Hiring for 2026-2027"). */
    private String title;

    /** Company name (e.g., "Deutsche Bank"). */
    private String company;

    /** List of job locations (e.g., ["Mumbai", "Pune", "Jaipur"]). */
    private List<String> locations;

    /** Date when the job was posted (e.g., "2026-07-17"). */
    private String postedDate;

    /** Job duration (e.g., "12 months"). Optional. */
    private String duration;

    /** Job description or summary. Optional. */
    private String description;

    // ── Constructors ──────────────────────────────────────────────

    public JobMetadata() {}

    public JobMetadata(String jobId, String title, String company, List<String> locations,
                       String postedDate, String duration, String description) {
        this.jobId = jobId;
        this.title = title;
        this.company = company;
        this.locations = locations;
        this.postedDate = postedDate;
        this.duration = duration;
        this.description = description;
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public List<String> getLocations() { return locations; }
    public void setLocations(List<String> locations) { this.locations = locations; }

    public String getPostedDate() { return postedDate; }
    public void setPostedDate(String postedDate) { this.postedDate = postedDate; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
