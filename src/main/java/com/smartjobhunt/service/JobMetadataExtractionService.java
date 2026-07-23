package com.smartjobhunt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.generativeai.ChatSession;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.smartjobhunt.dto.JobMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured metadata from job description PDFs using AI (Gemini).
 *
 * <p>When a job PDF is uploaded without explicit metadata, this service:
 * <ol>
 *   <li>Extracts the text content from the PDF using {@link ResumeParsingService}</li>
 *   <li>Sends the text to Gemini with a structured prompt</li>
 *   <li>Parses Gemini's JSON response into {@link JobMetadata}</li>
 * </ol>
 *
 * <p>This allows the system to automatically understand job context and create
 * meaningful metadata without relying on filename parsing.
 */
@Service
public class JobMetadataExtractionService {

    private static final Logger log = LoggerFactory.getLogger(JobMetadataExtractionService.class);

    private final ResumeParsingService resumeParsingService;
    private final VertexAI vertexAI;
    private final String geminiModel;
    private final ObjectMapper objectMapper;

    public JobMetadataExtractionService(
            ResumeParsingService resumeParsingService,
            VertexAI vertexAI,
            @Value("${gcp.vertex-ai.gemini-model}") String geminiModel,
            ObjectMapper objectMapper) {
        this.resumeParsingService = resumeParsingService;
        this.vertexAI = vertexAI;
        this.geminiModel = geminiModel;
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts structured job metadata from a PDF file using AI.
     *
     * @param file the job description PDF file
     * @return {@link JobMetadata} with extracted title, company, job ID, locations, etc.
     * @throws IOException if PDF parsing or AI request fails
     */
    public JobMetadata extractMetadata(MultipartFile file) throws IOException {
        // 1) Extract text from PDF
        String pdfText = resumeParsingService.extractText(file);
        
        if (pdfText == null || pdfText.trim().isEmpty()) {
            log.warn("PDF text extraction returned empty content. Using fallback metadata.");
            return createFallbackMetadata();
        }

        // 2) Truncate text to stay within Gemini context limits
        String truncatedText = truncateText(pdfText, 8000);

        // 3) Build prompt for Gemini
        String prompt = buildExtractionPrompt(truncatedText);

        // 4) Call Gemini
        try {
            String rawResponse = callGemini(prompt);
            
            // 5) Parse JSON response
            return parseGeminiResponse(rawResponse);
        } catch (Exception e) {
            log.error("Failed to extract metadata using Gemini: {}", e.getMessage(), e);
            return createFallbackMetadata();
        }
    }

    /**
     * Builds a structured prompt that instructs Gemini to extract job metadata as JSON.
     */
    private String buildExtractionPrompt(String jobText) {
        return """
                You are an expert HR assistant. Analyze the following job description and extract structured metadata.
                
                **Job Description:**
                
                %s
                
                ---
                
                **Instructions:**
                Extract the following information from the job description above and return it as a valid JSON object:
                
                {
                  "title": "The job title (e.g., 'Senior Backend Engineer', 'Marketing Manager')",
                  "jobId": "Job ID or reference number if present (e.g., 'R0357981', 'JOB-2024-001'), or 'N/A' if not found",
                  "company": "Company name (e.g., 'Deutsche Bank', 'Google'), or 'N/A' if not found",
                  "locations": ["City or location 1", "City or location 2", ...] (empty array if no locations found),
                  "postedDate": "Date when the job was posted in YYYY-MM-DD format, or empty string if not found",
                  "duration": "Employment duration (e.g., '12 months', 'Permanent', 'Contract'), or empty string if not found",
                  "description": "Brief summary of the role (1-2 sentences), or empty string if cannot be determined"
                }
                
                **Important:**
                - Return ONLY the JSON object, no additional text or explanation
                - Use "N/A" for jobId and company if they cannot be determined from the text
                - Use empty array [] for locations if no locations are mentioned
                - Use empty string "" for optional fields if they cannot be determined
                - Ensure the JSON is valid and properly formatted
                - If you cannot determine the title, use a descriptive title based on the role description
                """.formatted(jobText);
    }

    /**
     * Calls Gemini API with the extraction prompt.
     */
    private String callGemini(String prompt) throws IOException {
        GenerativeModel model = new GenerativeModel(geminiModel, vertexAI);
        
        // Configure for structured JSON output
        GenerationConfig config = GenerationConfig.newBuilder()
                .setTemperature(0.2f)  // Lower temperature for more consistent output
                .setTopK(10)
                .setTopP(0.8f)
                .build();
        
        model = model.withGenerationConfig(config);
        
        ChatSession chat = model.startChat();
        String response = ResponseHandler.getText(chat.sendMessage(prompt));
        
        log.debug("Gemini metadata extraction response: {}", response);
        return response;
    }

    /**
     * Parses Gemini's JSON response into a {@link JobMetadata} object.
     * Handles cases where JSON is embedded in markdown code blocks or surrounded by text.
     */
    private JobMetadata parseGeminiResponse(String rawResponse) throws IOException {
        String jsonStr = extractJson(rawResponse);
        
        if (jsonStr == null || jsonStr.isBlank()) {
            log.warn("Could not extract JSON from Gemini response. Using fallback.");
            return createFallbackMetadata();
        }

        try {
            return objectMapper.readValue(jsonStr, JobMetadata.class);
        } catch (Exception e) {
            log.error("Failed to parse Gemini JSON response: {}", e.getMessage());
            return createFallbackMetadata();
        }
    }

    /**
     * Extracts JSON from Gemini's response, which may be wrapped in markdown code blocks
     * or surrounded by explanatory text.
     */
    private String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // Try to extract JSON from markdown code block
        Pattern codeBlockPattern = Pattern.compile("```(?:json)?\\s*(.+?)\\s*```", Pattern.DOTALL);
        Matcher matcher = codeBlockPattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // Try to extract JSON by finding first { and last }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1).trim();
        }

        // If no JSON structure found, return the whole text and let parser try
        return text.trim();
    }

    /**
     * Creates fallback metadata when AI extraction fails or PDF text is empty.
     */
    private JobMetadata createFallbackMetadata() {
        JobMetadata metadata = new JobMetadata();
        metadata.setTitle("Untitled Job");
        metadata.setJobId("N/A");
        metadata.setCompany("N/A");
        metadata.setLocations(new ArrayList<>());
        metadata.setPostedDate("");
        metadata.setDuration("");
        metadata.setDescription("");
        return metadata;
    }

    /**
     * Truncates text to a maximum number of characters to stay within Gemini context limits.
     */
    private String truncateText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n\n... [truncated]";
    }
}
