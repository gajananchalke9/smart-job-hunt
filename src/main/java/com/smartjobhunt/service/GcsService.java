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
}
