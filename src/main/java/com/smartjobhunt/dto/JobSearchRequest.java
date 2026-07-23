package com.smartjobhunt.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/jobs/search}.
 */
public class JobSearchRequest {

    @NotBlank(message = "query must not be blank")
    private String query;

    /** Maximum number of results to return (default 10). */
    private int pageSize = 10;

    // ── Constructors ──────────────────────────────────────────────

    public JobSearchRequest() {}

    public JobSearchRequest(String query) { this.query = query; }

    // ── Getters & Setters ─────────────────────────────────────────

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
