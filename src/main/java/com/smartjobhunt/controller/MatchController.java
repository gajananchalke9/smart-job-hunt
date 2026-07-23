package com.smartjobhunt.controller;

import com.smartjobhunt.dto.JobSearchResult;
import com.smartjobhunt.dto.MatchResult;
import com.smartjobhunt.service.GeminiScoringService;
import com.smartjobhunt.service.ResumeParsingService;
import com.smartjobhunt.service.VertexSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;

/**
 * REST controller for the resume-matching endpoint.
 *
 * <ul>
 *   <li>{@code POST /api/match} — upload a resume PDF, retrieve candidate jobs from
 *       Vertex AI Search, score each via Gemini, and return them sorted by score
 *       descending.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/match")
@Tag(name = "Resume Matching", description = "APIs for intelligent resume-to-job matching using AI")
public class MatchController {

    /** Default query to retrieve broad candidate jobs when no keywords are extracted. */
    private static final String DEFAULT_QUERY = "software engineer job";

    /** Maximum number of candidate jobs to fetch for scoring. */
    private static final int CANDIDATE_PAGE_SIZE = 10;

    private final ResumeParsingService resumeParsingService;
    private final VertexSearchService vertexSearchService;
    private final GeminiScoringService geminiScoringService;

    public MatchController(
            ResumeParsingService resumeParsingService,
            VertexSearchService vertexSearchService,
            GeminiScoringService geminiScoringService) {
        this.resumeParsingService = resumeParsingService;
        this.vertexSearchService  = vertexSearchService;
        this.geminiScoringService = geminiScoringService;
    }

    // ─────────────────────────────────────────────────────────────
    // POST /api/match
    // ─────────────────────────────────────────────────────────────

    /**
     * Matches a resume PDF against all indexed job profiles.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Extract text from the resume PDF using PDFBox</li>
     *   <li>Build a keyword query from the resume text</li>
     *   <li>Retrieve candidate jobs from Vertex AI Search</li>
     *   <li>Score each job against the resume using Gemini (explainable 0–100)</li>
     *   <li>Sort results by score descending and return</li>
     * </ol>
     *
     * @param resumeFile the PDF file (multipart/form-data, field name {@code resume})
     * @return list of {@link MatchResult} sorted by {@code score} descending
     */
    @Operation(
            summary = "Match resume to job descriptions",
            description = "Uploads a resume PDF and intelligently matches it against all indexed job descriptions. "
                    + "Uses Vertex AI Search to find candidate jobs and Gemini AI to score each match with explainable reasoning. "
                    + "Returns jobs sorted by match score (0-100) in descending order."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Resume matched successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MatchResult.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid input - resume file is empty, not a PDF, or contains no extractable text"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during matching process"
            )
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<MatchResult>> matchResume(
            @Parameter(description = "Resume PDF file with selectable text", required = true)
            @RequestParam("resume") MultipartFile resumeFile) throws Exception {

        if (resumeFile.isEmpty()) {
            throw new IllegalArgumentException("Resume file is empty.");
        }
        if (!isPdf(resumeFile)) {
            throw new IllegalArgumentException("Only PDF resumes are accepted.");
        }

        // 1) Extract resume text
        String resumeText = resumeParsingService.extractText(resumeFile);
        if (resumeText.isBlank()) {
            throw new IllegalArgumentException(
                    "Could not extract text from the resume PDF. Ensure the PDF contains selectable text.");
        }

        // 2) Build a search query from the resume text (first 500 chars is usually enough)
        String searchQuery = buildSearchQuery(resumeText);

        // 3) Retrieve candidate jobs from Vertex AI Search
        List<JobSearchResult> candidateJobs =
                vertexSearchService.search(searchQuery, CANDIDATE_PAGE_SIZE);

        if (candidateJobs.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // 4) Score each candidate job with Gemini
        List<MatchResult> scoredResults =
                geminiScoringService.scoreJobs(candidateJobs, resumeText);

        // 5) Sort by score descending (best match first)
        scoredResults.sort(Comparator.comparingInt(MatchResult::getScore).reversed());

        return ResponseEntity.ok(scoredResults);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Derives a short search query from the resume text.
     *
     * <p>Currently takes the first 500 characters of the resume (which typically
     * contains the candidate's name, headline, and top skills) and uses that as the
     * Vertex AI Search query. This is a simple but effective baseline; a more
     * sophisticated implementation could extract named entities or skill keywords.
     */
    private String buildSearchQuery(String resumeText) {
        String trimmed = resumeText.trim();
        if (trimmed.isBlank()) {
            return DEFAULT_QUERY;
        }
        // Use the first 500 characters as the search query
        String snippet = trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
        // Replace newlines with spaces for a cleaner query
        return snippet.replaceAll("\\s+", " ").trim();
    }

    private boolean isPdf(MultipartFile file) {
        String contentType = file.getContentType();
        String filename    = file.getOriginalFilename();
        return "application/pdf".equalsIgnoreCase(contentType)
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }
}
