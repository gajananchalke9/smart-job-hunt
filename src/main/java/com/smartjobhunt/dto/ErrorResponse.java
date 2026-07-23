package com.smartjobhunt.dto;

/**
 * Standard error response envelope returned by the global exception handler.
 */
public class ErrorResponse {

    private int status;
    private String error;
    private String message;

    // ── Constructors ──────────────────────────────────────────────

    public ErrorResponse() {}

    public ErrorResponse(int status, String error, String message) {
        this.status = status;
        this.error = error;
        this.message = message;
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
