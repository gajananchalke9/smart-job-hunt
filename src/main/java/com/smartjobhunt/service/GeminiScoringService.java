package com.smartjobhunt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.smartjobhunt.dto.JobSearchResult;
import com.smartjobhunt.dto.MatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses Google Gemini (via Vertex AI) to generate an explainable match score
 * between a candidate's resume and a job description.
 *
 * <p>The service builds a structured prompt and instructs Gemini to return
 * <strong>strict JSON</strong> matching the {@link MatchResult} DTO layout:
 * <pre>
 * {
 *   "score": 85,
 *   "strengths": ["..."],
 *   "gaps": ["..."],
 *   "summary": "..."
 * }
 * </pre>
 */
@Service
public class GeminiScoringService {

    private static final Logger log = LoggerFactory.getLogger(GeminiScoringService.class);

    private static final Pattern JSON_BLOCK_PATTERN =
            Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);

    private final VertexAI vertexAI;
    private final String geminiModel;
    private final ObjectMapper objectMapper;

    public GeminiScoringService(
            VertexAI vertexAI,
            @Value("${gcp.vertex-ai.gemini-model:gemini-1.5-flash}") String geminiModel,
            ObjectMapper objectMapper) {
        this.vertexAI = vertexAI;
        this.geminiModel = geminiModel;
        this.objectMapper = objectMapper;
    }

    /**
     * Scores a list of candidate jobs against a resume text using Gemini.
     *
     * <p>For each job a separate Gemini call is made. The results are collected
     * and returned as-is; the caller is responsible for sorting by score.
     *
     * @param jobs       candidate jobs retrieved from Vertex AI Search
     * @param resumeText plain-text content of the candidate's resume
     * @return list of {@link MatchResult} objects (not yet sorted)
     */
    public List<MatchResult> scoreJobs(List<JobSearchResult> jobs, String resumeText) {
        log.info("Starting job scoring with Gemini - jobCount: {}, resumeLength: {} characters",
                jobs.size(), resumeText.length());
        
        List<MatchResult> results = new ArrayList<>();

        GenerativeModel model = new GenerativeModel(geminiModel, vertexAI);
        log.debug("Using Gemini model: {}", geminiModel);
        
        int successCount = 0;
        int failCount = 0;
        
        for (JobSearchResult job : jobs) {
            try {
                log.debug("Scoring job: documentId={}, title='{}'", job.getDocumentId(), job.getTitle());
                MatchResult result = scoreJob(model, job, resumeText);
                results.add(result);
                successCount++;
                log.debug("Job scored successfully: documentId={}, score={}", job.getDocumentId(), result.getScore());
            } catch (Exception e) {
                // If a single job fails to score, log the error and add a default entry
                failCount++;
                log.error("Failed to score job {}: {}", job.getDocumentId(), e.getMessage(), e);
                results.add(buildFallbackResult(job));
            }
        }

        log.info("Job scoring completed - total: {}, successful: {}, failed: {}",
                jobs.size(), successCount, failCount);
        return results;
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private MatchResult scoreJob(GenerativeModel model, JobSearchResult job, String resumeText)
            throws IOException {

        log.debug("Building Gemini prompt for job: {}", job.getDocumentId());
        String prompt = buildPrompt(job, resumeText);
        
        log.debug("Sending request to Gemini for job: {}", job.getDocumentId());
        GenerateContentResponse response = model.generateContent(prompt);
        String rawText = ResponseHandler.getText(response);
        log.debug("Received response from Gemini for job: {}, responseLength: {} characters",
                job.getDocumentId(), rawText.length());

        log.debug("Parsing Gemini JSON response for job: {}", job.getDocumentId());
        MatchResult result = parseGeminiJson(rawText);
        result.setDocumentId(job.getDocumentId());
        result.setTitle(job.getTitle());
        result.setGcsUri(job.getGcsUri());
        
        log.info("Job scored: documentId={}, score={}, strengths={}, gaps={}",
                job.getDocumentId(), result.getScore(), 
                result.getStrengths().size(), result.getGaps().size());
        
        return result;
    }

    /**
     * Builds the Gemini prompt that requests an explainable JSON match score.
     */
    private String buildPrompt(JobSearchResult job, String resumeText) {
        return """
                You are an expert recruiter and talent-matching engine.
                
                You will be given a JOB DESCRIPTION and a CANDIDATE RESUME.
                Your task is to evaluate how well the candidate fits the job.
                
                Return ONLY a valid JSON object — no markdown, no code fences, no extra text.
                The JSON MUST have exactly these fields:
                {
                  "score": <integer 0-100>,
                  "strengths": [<short string>, ...],
                  "gaps": [<short string>, ...],
                  "summary": "<one concise sentence>"
                }
                
                Rules:
                - score: 0 = no match, 100 = perfect match
                - strengths: 2-5 bullet-point strings listing what the candidate does well for this role
                - gaps: 2-5 bullet-point strings listing what the candidate is missing
                - summary: a single sentence explanation of the overall match
                
                ---
                JOB DESCRIPTION (title: %s):
                %s
                
                ---
                CANDIDATE RESUME:
                %s
                
                ---
                Return ONLY the JSON object now.
                """.formatted(
                job.getTitle(),
                truncate(job.getSnippet(), 2000),
                truncate(resumeText, 4000));
    }

    /**
     * Parses the Gemini response text into a {@link MatchResult}.
     *
     * <p>Handles three common output formats:
     * <ol>
     *   <li>Pure JSON (ideal)</li>
     *   <li>JSON wrapped in a Markdown code fence (```json ... ```)</li>
     *   <li>JSON embedded within surrounding text (extracted via regex)</li>
     * </ol>
     */
    private MatchResult parseGeminiJson(String rawText) throws IOException {
        String json = rawText.trim();

        // 1) Try to strip Markdown code fences (```json ... ``` or ``` ... ```)
        Matcher fenceMatcher = JSON_BLOCK_PATTERN.matcher(json);
        if (fenceMatcher.find()) {
            log.debug("Extracted JSON from markdown code fence");
            json = fenceMatcher.group(1).trim();
        } else {
            // 2) Extract the first {...} block if there is surrounding text
            int start = json.indexOf('{');
            int end   = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                log.debug("Extracted JSON block from response text");
                json = json.substring(start, end + 1);
            }
        }

        log.debug("Parsing JSON to MatchResult - jsonLength: {} characters", json.length());
        return objectMapper.readValue(json, MatchResult.class);
    }

    /** Creates a safe fallback {@link MatchResult} when Gemini scoring fails. */
    private MatchResult buildFallbackResult(JobSearchResult job) {
        log.warn("Creating fallback result for job: {}", job.getDocumentId());
        MatchResult result = new MatchResult();
        result.setDocumentId(job.getDocumentId());
        result.setTitle(job.getTitle());
        result.setGcsUri(job.getGcsUri());
        result.setScore(0);
        result.setStrengths(List.of());
        result.setGaps(List.of("Scoring unavailable"));
        result.setSummary("Could not generate a match score for this job.");
        return result;
    }

    /** Truncates text to {@code maxChars} to stay within Gemini context limits. */
    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
