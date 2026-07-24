package com.smartjobhunt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.smartjobhunt.dto.JobMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles all interactions with Google Cloud Storage (GCS).
 *
 * <p>Job description PDFs are uploaded into the configured bucket under the
 * {@code Job-profiles/} prefix. The returned GCS URI can be used by Vertex AI Search
 * when importing documents.
 */
@Service
public class GcsService {

    private static final Logger log = LoggerFactory.getLogger(GcsService.class);

    private final Storage storage;
    private final String bucketName;
    private final ObjectMapper objectMapper;

    public GcsService(Storage storage,
                      @Value("${gcp.gcs.bucket}") String bucketName,
                      ObjectMapper objectMapper) {
        this.storage = storage;
        this.bucketName = bucketName;
        this.objectMapper = objectMapper;
    }

    /**
     * Result of uploading a job with metadata.
     */
    public static class UploadResult {
        private final String documentId;
        private final String jsonlGcsUri;

        public UploadResult(String documentId, String jsonlGcsUri) {
            this.documentId = documentId;
            this.jsonlGcsUri = jsonlGcsUri;
        }

        public String getDocumentId() { return documentId; }
        public String getJsonlGcsUri() { return jsonlGcsUri; }
    }

    /**
     * Uploads a job with both PDF and metadata JSONL file to GCS.
     * Creates a JSONL file containing structured metadata for Vertex AI Search.
     *
     * @param file     the multipart PDF file to upload
     * @param metadata structured metadata for the job (title, job_id, company, etc.)
     * @return UploadResult containing the document ID and JSONL GCS URI
     * @throws IOException if reading the file bytes fails or JSON serialization fails
     */
    public UploadResult uploadJobWithMetadata(MultipartFile file, JobMetadata metadata) throws IOException {
        // Generate a unique document ID
        String documentId = UUID.randomUUID().toString();
        log.info("Starting job upload to GCS - documentId: {}, filename: {}, size: {} bytes",
                documentId, file.getOriginalFilename(), file.getSize());
        
        // Use the document ID for the filename instead of original filename
        // This ensures filenames are unique and not based on user-provided titles
        String pdfObjectName = "Job-profiles/" + documentId + ".pdf";
        log.debug("Uploading PDF to GCS - objectName: {}", pdfObjectName);
        String pdfGcsUri = uploadFile(file.getBytes(), pdfObjectName, "application/pdf");
        log.info("PDF uploaded successfully to GCS - uri: {}", pdfGcsUri);
        
        // Create JSONL metadata
        log.debug("Creating JSONL metadata for document: {}", documentId);
        String jsonlContent = createJsonlMetadata(documentId, metadata, pdfGcsUri);
        log.debug("JSONL metadata created - size: {} bytes", jsonlContent.length());
        
        // Upload JSONL metadata file
        String jsonlObjectName = "Job-profiles/" + documentId + ".jsonl";
        log.debug("Uploading JSONL metadata to GCS - objectName: {}", jsonlObjectName);
        String jsonlGcsUri = uploadFile(
            jsonlContent.getBytes(StandardCharsets.UTF_8),
            jsonlObjectName,
            "application/jsonl"
        );
        log.info("JSONL metadata uploaded successfully to GCS - uri: {}", jsonlGcsUri);
        
        return new UploadResult(documentId, jsonlGcsUri);
    }

    /**
     * Creates a JSONL line with structured metadata for Vertex AI Search.
     * Format matches the example provided in the problem statement.
     *
     * <p>Required fields (title, job_id, company) should be non-null. If any required
     * field is null, it will be serialized as null in the JSON output, which may cause
     * search issues. Callers should ensure these fields are populated before calling this method.
     *
     * @param documentId unique document identifier
     * @param metadata   job metadata (should have title, jobId, and company populated)
     * @param pdfGcsUri  GCS URI of the uploaded PDF
     * @return JSONL formatted string
     * @throws IOException if JSON serialization fails
     */
    private String createJsonlMetadata(String documentId, JobMetadata metadata, String pdfGcsUri)
            throws IOException {
        Map<String, Object> jsonlEntry = new HashMap<>();
        // Use "id" for "document" schema imports in Vertex AI Search (content.uri references
        // the PDF so it is indexed for full-text search alongside the structData metadata).
        jsonlEntry.put("id", documentId);
        
        // Structured data for search and display
        Map<String, Object> structData = new HashMap<>();
        structData.put("title", metadata.getTitle());
        structData.put("job_id", metadata.getJobId());
        structData.put("company", metadata.getCompany());
        if (metadata.getLocations() != null && !metadata.getLocations().isEmpty()) {
            structData.put("locations", metadata.getLocations());
        }
        if (metadata.getPostedDate() != null && !metadata.getPostedDate().isBlank()) {
            structData.put("posted_date", metadata.getPostedDate());
        }
        if (metadata.getDuration() != null && !metadata.getDuration().isBlank()) {
            structData.put("duration", metadata.getDuration());
        }
        if (metadata.getDescription() != null && !metadata.getDescription().isBlank()) {
            structData.put("description", metadata.getDescription());
        }
        jsonlEntry.put("structData", structData);
        
        // Content reference to the PDF
        Map<String, Object> content = new HashMap<>();
        content.put("mimeType", "application/pdf");
        content.put("uri", pdfGcsUri);
        jsonlEntry.put("content", content);
        
        return objectMapper.writeValueAsString(jsonlEntry);
    }

    /**
     * Uploads raw bytes to GCS.
     *
     * @param data        the bytes to upload
     * @param objectName  the object name in the bucket
     * @param contentType the MIME type
     * @return the GCS URI
     */
    private String uploadFile(byte[] data, String objectName, String contentType) {
        log.debug("Uploading file to GCS - bucket: {}, objectName: {}, contentType: {}, size: {} bytes",
                bucketName, objectName, contentType, data.length);
        
        BlobId blobId = BlobId.of(bucketName, objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build();

        storage.create(blobInfo, data);
        String gcsUri = "gs://" + bucketName + "/" + objectName;
        
        log.debug("File uploaded successfully to GCS - uri: {}", gcsUri);
        return gcsUri;
    }
}
