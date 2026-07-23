package com.smartjobhunt.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/jobs/search}.
 */
@Schema(description = "Request object for searching job descriptions")
public class JobSearchRequest {

    @Schema(description = "Natural language search query", example = "Senior Java Developer with Spring Boot experience")
    @NotBlank(message = "query must not be blank")
    private String query;

    @Schema(description = "Maximum number of results to return", example = "10", defaultValue = "10")
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
