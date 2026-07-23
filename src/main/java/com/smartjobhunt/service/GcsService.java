package com.smartjobhunt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.smartjobhunt.dto.JobMetadata;
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
 * {@code jobs/} prefix. The returned GCS URI can be used by Vertex AI Search
 * when importing documents.
 */
@Service
public class GcsService {

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
     * Uploads a PDF file to GCS.
     *
     * @param file      the multipart PDF file to upload
     * @param objectName the desired object name inside the bucket (e.g. {@code jobs/my-job.pdf}).
     *                   If {@code null} a UUID-based name is generated automatically.
     * @return the GCS URI in the form {@code gs://<bucket>/<objectName>}
     * @throws IOException if reading the file bytes fails
     */
    public String uploadPdf(MultipartFile file, String objectName) throws IOException {
        if (objectName == null || objectName.isBlank()) {
            String originalFilename = file.getOriginalFilename();
            String safeName = (originalFilename != null && !originalFilename.isBlank())
                    ? sanitiseFilename(originalFilename)
                    : UUID.randomUUID() + ".pdf";
            objectName = "jobs/" + safeName;
        }

        BlobId blobId = BlobId.of(bucketName, objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("application/pdf")
                .build();

        storage.create(blobInfo, file.getBytes());

        return "gs://" + bucketName + "/" + objectName;
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
        
        // Sanitise the original filename for the PDF
        String originalFilename = file.getOriginalFilename();
        String safeName = (originalFilename != null && !originalFilename.isBlank())
                ? sanitiseFilename(originalFilename)
                : documentId + ".pdf";
        
        // Upload the PDF to GCS
        String pdfObjectName = "jobs/" + safeName;
        String pdfGcsUri = uploadFile(file.getBytes(), pdfObjectName, "application/pdf");
        
        // Create JSONL metadata
        String jsonlContent = createJsonlMetadata(documentId, metadata, pdfGcsUri);
        
        // Upload JSONL metadata file
        String jsonlObjectName = "jobs/" + removeExtension(safeName) + ".jsonl";
        String jsonlGcsUri = uploadFile(
            jsonlContent.getBytes(StandardCharsets.UTF_8),
            jsonlObjectName,
            "application/jsonl"
        );
        
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
        BlobId blobId = BlobId.of(bucketName, objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build();

        storage.create(blobInfo, data);

        return "gs://" + bucketName + "/" + objectName;
    }

    /**
     * Sanitises a filename so it is safe for use as a GCS object name.
     * Replaces spaces with underscores and strips characters that are
     * not alphanumeric, dots, underscores, or hyphens.
     */
    private String sanitiseFilename(String filename) {
        return filename.trim()
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-zA-Z0-9._\\-]", "");
    }

    /**
     * Removes the file extension from a filename.
     * For example: "foo.pdf" -> "foo", "bar.txt" -> "bar"
     */
    private String removeExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return filename;
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }
}
