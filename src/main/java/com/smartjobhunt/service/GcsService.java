package com.smartjobhunt.service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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

    public GcsService(Storage storage,
                      @Value("${gcp.gcs.bucket}") String bucketName) {
        this.storage = storage;
        this.bucketName = bucketName;
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
     * Sanitises a filename so it is safe for use as a GCS object name.
     * Replaces spaces with underscores and strips characters that are
     * not alphanumeric, dots, underscores, or hyphens.
     */
    private String sanitiseFilename(String filename) {
        return filename.trim()
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-zA-Z0-9._\\-]", "");
    }
}
