# Smart Job Hunt 🎯

A **Java 17 + Spring Boot 3** backend that lets you store job-profile-description PDFs, search them via natural-language prompts, and match a candidate resume against every indexed job — with an explainable AI score powered by **Google Gemini**.

Also includes a **Python utility module** for local job profile management, keyword-based search, and term-overlap scoring.

---

## Architecture

```
Job PDFs ──▶ GCS bucket ──▶ Vertex AI Search datastore (indexed)
Resume PDF ──▶ PDFBox text extraction ──▶ Vertex AI Search retrieval ──▶ Gemini scoring (0–100 + reasoning)
```

| Layer | Technology |
|-------|-----------|
| REST API | Spring Boot 3 (Spring Web) |
| API Documentation | SpringDoc OpenAPI (Swagger) |
| Storage | Google Cloud Storage (GCS) |
| Search / Retrieval | Vertex AI Search (Discovery Engine) |
| Scoring / Reasoning | Gemini 1.5 Flash via Vertex AI |
| PDF parsing | Apache PDFBox 3 |
| Build | Maven 3 |

---

## Prerequisites

### GCP project & APIs

1. **Create a GCP project** (or use an existing one) and note your **Project ID**.
2. Enable the following APIs in [Google Cloud Console](https://console.cloud.google.com/) → *APIs & Services → Enable APIs*:
   - **Cloud Storage API** (`storage.googleapis.com`)
   - **Discovery Engine API** (`discoveryengine.googleapis.com`)
   - **Vertex AI API** (`aiplatform.googleapis.com`)

### GCS bucket

```bash
gcloud storage buckets create gs://hackathon-db-2026 \
  --project=YOUR_PROJECT_ID \
  --location=us-central1
```

The bucket will contain:
- **Job-profiles/** - Job description PDFs and metadata files
- **log/** - Error logs from Vertex AI Search document imports

### Vertex AI Search datastore

1. Go to **Vertex AI Search** → *Apps → New app → Search → Generic*.
2. Choose **Unstructured documents** as the data type.
3. Note the **Datastore ID** (visible in the URL or the datastore details page).
4. The default serving config is `default_config` — used automatically by the backend.

### Authentication (Application Default Credentials)

The application uses ADC for authentication. In development:

```bash
gcloud auth application-default login
```

In production (GCE, Cloud Run, GKE) the default service account is used automatically.  
For a service-account key file set:

```bash
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
```

> **⚠️ Never commit credentials to source control.**

---

## Configuration

Edit `src/main/resources/application.yml` and replace every `YOUR_*` placeholder:

```yaml
gcp:
  project-id: YOUR_GCP_PROJECT_ID           # e.g. my-gcp-project-123
  location: us-central1                     # Vertex AI region (Gemini)
  discovery-engine-location: global         # Vertex AI Search location
  gcs:
    bucket: hackathon-db-2026               # GCS bucket (default: hackathon-db-2026)
  discovery-engine:
    datastore-id: YOUR_DATASTORE_ID
  vertex-ai:
    gemini-model: gemini-1.5-flash          # or gemini-1.5-pro
```

Alternatively, override any property via environment variable using Spring's relaxed binding, e.g.:

```bash
export GCP_PROJECT_ID=my-project
export GCP_GCS_BUCKET=hackathon-db-2026
export GCP_DISCOVERY_ENGINE_DATASTORE_ID=my-datastore
```

---

## Build & Run

### Build

```bash
mvn clean package -DskipTests
```

### Run

```bash
mvn spring-boot:run
```

Or run the fat JAR directly:

```bash
java -jar target/smart-job-hunt-1.0.0-SNAPSHOT.jar
```

The server starts on **http://localhost:8080**.

### API Documentation (Swagger)

Once the application is running, you can explore the API documentation:

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI Spec (JSON)**: http://localhost:8080/v3/api-docs
- **OpenAPI Spec (YAML)**: http://localhost:8080/v3/api-docs.yaml

The Swagger UI provides an interactive interface to:
- View all available endpoints with detailed descriptions
- See request/response schemas
- Try out API calls directly from the browser
- Download the OpenAPI specification

---

## REST API

### 1. Upload a job description PDF

**`POST /api/jobs/upload`**

Uploads a job-description PDF to GCS along with structured metadata and imports it into the Vertex AI Search datastore for indexing.

**With metadata (recommended):**

```bash
curl -X POST http://localhost:8080/api/jobs/upload \
  -F "file=@/path/to/job-description.pdf" \
  -F 'metadata={
    "jobId": "R0357981",
    "title": "Apprentice Hiring for 2026-2027",
    "company": "Deutsche Bank",
    "locations": ["Mumbai", "Pune", "Jaipur", "Bangalore", "New Delhi"],
    "postedDate": "2026-07-17",
    "duration": "12 months",
    "description": "Exciting opportunity for fresh graduates to join our apprenticeship program."
  }'
```

**Without metadata (AI extraction):**

```bash
curl -X POST http://localhost:8080/api/jobs/upload \
  -F "file=@/path/to/job-description.pdf"
```

When metadata is not provided, the system will:
1. Extract text content from the PDF
2. Use AI (Gemini) to analyze the content and extract structured metadata
3. Automatically generate title, company, job ID, locations, and other fields based on the PDF content

This ensures meaningful metadata is created from the actual job description, not just the filename.

**Response:**
```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "gcsUri": "gs://hackathon-db-2026/Job-profiles/job-description.jsonl",
  "message": "Job uploaded and indexed successfully with metadata."
}
```

**How it works:**

1. The PDF is uploaded to GCS with a unique UUID-based filename (e.g., `gs://hackathon-db-2026/Job-profiles/550e8400-e29b-41d4-a716-446655440000.pdf`)
2. If metadata is not provided, the system extracts it from the PDF content using AI
3. A JSONL metadata file is created with the structured data:
   ```json
   {
     "_id": "550e8400-e29b-41d4-a716-446655440000",
     "structData": {
       "title": "Apprentice Hiring for 2026-2027",
       "job_id": "R0357981",
       "company": "Deutsche Bank",
       "locations": ["Mumbai", "Pune", "Jaipur", "Bangalore", "New Delhi"],
       "posted_date": "2026-07-17",
       "duration": "12 months",
       "description": "Exciting opportunity..."
     },
     "content": {
       "mimeType": "application/pdf",
       "uri": "gs://hackathon-db-2026/Job-profiles/550e8400-e29b-41d4-a716-446655440000.pdf"
     }
   }
   ```
4. The JSONL file is uploaded to GCS (e.g., `gs://hackathon-db-2026/Job-profiles/550e8400-e29b-41d4-a716-446655440000.jsonl`)
5. The JSONL is imported into Vertex AI Search, which indexes both the structured metadata and the PDF content

---

### 2. Search job profiles

**`POST /api/jobs/search`**

Searches the indexed job profiles with a natural-language query.

```bash
curl -X POST http://localhost:8080/api/jobs/search \
  -H "Content-Type: application/json" \
  -d '{"query": "senior backend engineer with Kubernetes experience", "pageSize": 5}'
```

**Response:**
```json
[
  {
    "documentId": "abc123",
    "title": "Apprentice Hiring for 2026-2027",
    "snippet": "Exciting opportunity for fresh graduates to join our apprenticeship program...",
    "gcsUri": "gs://hackathon-db-2026/Job-profiles/apprentice-hiring.pdf",
    "relevanceScore": 0.0
  }
]
```

The search results now include proper titles from the structured metadata instead of document IDs.

---

### 3. Match a resume against all jobs

**`POST /api/match`**

Extracts text from the uploaded resume PDF, retrieves candidate jobs from Vertex AI Search, then asks Gemini to produce an explainable match score (0–100) for each — returned sorted best-match first.

```bash
curl -X POST http://localhost:8080/api/match \
  -F "resume=@/path/to/my-resume.pdf"
```

**Response:**
```json
[
  {
    "documentId": "abc123",
    "title": "Senior Backend Engineer",
    "gcsUri": "gs://hackathon-db-2026/Job-profiles/senior-backend.pdf",
    "score": 88,
    "strengths": [
      "5+ years Java experience aligns with requirements",
      "Kubernetes and Docker expertise matches the role",
      "Strong Spring Boot background"
    ],
    "gaps": [
      "No demonstrated experience with Go",
      "Missing CNCF certifications mentioned in JD"
    ],
    "summary": "Strong candidate with excellent backend skills and minor gaps in Go and cloud certifications."
  },
  {
    "documentId": "def456",
    "title": "DevOps Engineer",
    "gcsUri": "gs://hackathon-db-2026/Job-profiles/devops.pdf",
    "score": 62,
    "strengths": ["Docker/Kubernetes background relevant"],
    "gaps": ["Lacks Terraform experience", "No CI/CD pipeline ownership mentioned"],
    "summary": "Partial match — DevOps tooling gaps may require upskilling."
  }
]
```

---

## Project Structure

```
src/main/java/com/smartjobhunt/
├── SmartJobHuntApplication.java     # Spring Boot entry point
├── config/
│   ├── GcpConfig.java               # GCP client Spring beans (Storage, DiscoveryEngine, VertexAI)
│   └── OpenApiConfig.java           # Swagger/OpenAPI configuration
├── controller/
│   ├── JobController.java           # POST /api/jobs/upload, POST /api/jobs/search
│   └── MatchController.java         # POST /api/match
├── service/
│   ├── GcsService.java              # Upload PDFs to GCS
│   ├── VertexSearchService.java     # Search + import documents (Vertex AI Search)
│   ├── ResumeParsingService.java    # Extract text from PDF with PDFBox
│   └── GeminiScoringService.java    # Gemini explainable scoring
├── dto/
│   ├── JobSearchRequest.java
│   ├── JobSearchResult.java
│   ├── JobUploadResponse.java
│   ├── MatchResult.java
│   └── ErrorResponse.java
└── exception/
    └── GlobalExceptionHandler.java  # @RestControllerAdvice – consistent error responses
```

---

## Error Handling

All errors are returned as a JSON object:

```json
{
  "status": 400,
  "error": "Bad request",
  "message": "Only PDF files are accepted."
}
```

| Scenario | HTTP status |
|----------|------------|
| Empty / non-PDF file | `400 Bad Request` |
| Missing / blank query | `400 Bad Request` |
| File exceeds 20 MB limit | `413 Payload Too Large` |
| Any unhandled exception | `500 Internal Server Error` |

---

## Python Utility Module

The `smart_job_hunt` package provides a lightweight local datastore for job profile PDFs with keyword-based search and term-overlap scoring — useful for development and testing without GCP connectivity.

### Features
- Store job profile PDF metadata in a local JSON datastore
- Optional upload of job profile PDFs to Google Cloud Storage (GCS)
- Optional Vertex AI Search integration hook for prompt-based retrieval
- Score job profiles against a resume using term-overlap relevance scoring

### Add a job profile
```bash
python -m smart_job_hunt.job_datastore add \
  --job-id backend-1 \
  --title "Backend Python Engineer" \
  --pdf /absolute/path/job_profile.pdf \
  --description "Python FastAPI Docker PostgreSQL"
```

### Search job profiles with a prompt and resume
```bash
python -m smart_job_hunt.job_datastore search \
  --prompt "python fastapi backend roles" \
  --resume /absolute/path/resume.txt \
  --top-k 5
```

### Programmatic usage
```python
from smart_job_hunt.job_datastore import JobProfileDatastore

ds = JobProfileDatastore(datastore_path="data/job_profiles.json")
# Add profiles with add_job_profile(...)
# Search and score with search_jobs(prompt, resume_text)
```

### Python Tests
```bash
python -m unittest discover -s tests -p 'test_*.py'
```

---

## Logging

The application includes comprehensive logging to help you understand what's happening during execution.

### Log Levels

The application uses the following log levels configured in `application.yml`:

- **Root level**: `INFO` - General application logs
- **Application logs** (`com.smartjobhunt`): `DEBUG` - Detailed application-specific logs
- **Spring Web**: `INFO` - HTTP request/response logs
- **Google Cloud**: `INFO` - GCP service logs

### What Gets Logged

The application logs the following events:

#### Application Startup
- Application start and ready events
- Configuration details (Swagger UI URLs)

#### Job Upload (`POST /api/jobs/upload`)
- Incoming file details (filename, size)
- Metadata parsing or AI extraction start/completion
- GCS upload progress (PDF and JSONL)
- Vertex AI Search import start/completion
- Document IDs and URIs
- Errors and warnings

#### Job Search (`POST /api/jobs/search`)
- Search query and page size
- Number of results found
- Individual result details (document ID, title)

#### Resume Matching (`POST /api/match`)
- Incoming resume file details
- PDF text extraction progress
- Search query building
- Candidate job retrieval
- Gemini scoring for each job (score, strengths, gaps)
- Final sorted results

#### Error Handling
- All validation errors
- Constraint violations
- Illegal arguments
- File size limit exceeded
- Unhandled exceptions with full stack traces

### Viewing Logs

When running the application, logs appear in the console with timestamps and log levels:

```
2026-07-23 23:16:32.123 [main] INFO  c.s.SmartJobHuntApplication - Starting Smart Job Hunt application...
2026-07-23 23:16:35.456 [main] INFO  c.s.SmartJobHuntApplication - ========================================
2026-07-23 23:16:35.457 [main] INFO  c.s.SmartJobHuntApplication - Smart Job Hunt application is ready!
2026-07-23 23:16:35.458 [main] INFO  c.s.SmartJobHuntApplication - Swagger UI: http://localhost:8080/swagger-ui.html
2026-07-23 23:16:35.459 [main] INFO  c.s.SmartJobHuntApplication - ========================================
2026-07-23 23:16:40.123 [http-nio-8080-exec-1] INFO  c.s.c.JobController - Received job upload request - filename: job.pdf, size: 123456 bytes, metadata provided: false
```

### Customizing Log Levels

To change log levels, modify `src/main/resources/application.yml`:

```yaml
logging:
  level:
    root: INFO                    # Change to DEBUG for more verbose output
    com.smartjobhunt: DEBUG       # Application-specific logs
    org.springframework.web: INFO # Spring framework logs
    com.google.cloud: INFO        # GCP SDK logs
```

Or use environment variables:

```bash
export LOGGING_LEVEL_ROOT=DEBUG
export LOGGING_LEVEL_COM_SMARTJOBHUNT=TRACE
mvn spring-boot:run
```

---

## Troubleshooting

### Application not returning any results

If you're experiencing issues where the application is not returning any results, follow these steps:

#### 1. Check Configuration

When the application starts, it validates your configuration. Look for these lines in the logs:

```
✓ GCP Project ID: your-project-id
✓ GCS Bucket: your-bucket-name
✓ Vertex AI Search Datastore ID: your-datastore-id
```

If you see error messages like:
```
❌ GCP Project ID is not configured!
```

Make sure you've replaced all `YOUR_*` placeholders in `application.yml` with actual values, or set the corresponding environment variables.

#### 2. Verify GCP Authentication

The application uses Application Default Credentials (ADC). Verify authentication:

```bash
gcloud auth application-default login
```

Or if using a service account:
```bash
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
```

#### 3. Check Vertex AI Search Datastore

Make sure your Vertex AI Search datastore has indexed documents:

1. Go to [Vertex AI Search Console](https://console.cloud.google.com/gen-app-builder/engines)
2. Select your datastore
3. Check the "Documents" tab to see if documents are indexed
4. If no documents exist, upload some job PDFs using the `/api/jobs/upload` endpoint

#### 4. Verify Document Format

If you have old documents uploaded before PR#4 (before JSONL metadata support), they might not have proper structured metadata. To fix:

**Option A: Re-upload documents**
```bash
# Upload with metadata
curl -X POST http://localhost:8080/api/jobs/upload \
  -F "file=@job.pdf" \
  -F 'metadata={"title":"Software Engineer","company":"Acme Corp","jobId":"JOB-001"}'
```

**Option B: Upload without metadata (AI extraction)**
```bash
# The system will extract metadata from PDF content
curl -X POST http://localhost:8080/api/jobs/upload \
  -F "file=@job.pdf"
```

#### 5. Test Each Endpoint

**Test job upload:**
```bash
curl -X POST http://localhost:8080/api/jobs/upload \
  -F "file=@sample/Job-profiles/your-job.pdf"
```

Expected response:
```json
{
  "documentId": "550e8400-e29b-41d4-a716-446655440000",
  "gcsUri": "gs://hackathon-db-2026/Job-profiles/550e8400-e29b-41d4-a716-446655440000.jsonl",
  "message": "Job uploaded and indexed successfully with metadata."
}
```

**Test job search:**
```bash
curl -X POST http://localhost:8080/api/jobs/search \
  -H "Content-Type: application/json" \
  -d '{"query":"software engineer","pageSize":5}'
```

Expected response:
```json
[
  {
    "documentId": "abc123",
    "title": "Software Engineer",
    "snippet": "We are looking for...",
    "gcsUri": "gs://hackathon-db-2026/Job-profiles/file.pdf",
    "relevanceScore": 0.0
  }
]
```

**Test resume matching:**
```bash
curl -X POST http://localhost:8080/api/match \
  -F "resume=@your-resume.pdf"
```

#### 6. Check Logs

Enable DEBUG logging to see detailed information:

```bash
export LOGGING_LEVEL_COM_SMARTJOBHUNT=DEBUG
mvn spring-boot:run
```

Look for error messages or exceptions in the logs. Common issues:
- `Permission denied`: Check GCP IAM permissions
- `404 Not Found`: Verify datastore ID and bucket name
- `Invalid credentials`: Check authentication setup
- `No documents found`: Upload documents to the datastore

#### 7. Common Error Messages

| Error | Cause | Solution |
|-------|-------|----------|
| "Uploaded file is empty" | Empty PDF file | Ensure the PDF file is not corrupted |
| "Only PDF files are accepted" | Wrong file type | Upload only PDF files |
| "Could not extract text from the resume PDF" | Scanned image PDF | Use PDFs with selectable text, not scanned images |
| "No candidate jobs found for resume" | Empty datastore | Upload job PDFs first using `/api/jobs/upload` |
| "Scoring unavailable" | Gemini API error | Check Vertex AI API is enabled and credentials are valid |

#### 8. Verify GCP APIs

Ensure these APIs are enabled in your GCP project:

```bash
gcloud services enable storage.googleapis.com
gcloud services enable discoveryengine.googleapis.com
gcloud services enable aiplatform.googleapis.com
```

#### 9. Test with Sample Data

The repository includes sample data in the `sample/` directory. Try uploading a sample job:

```bash
ls sample/Job-profiles/
curl -X POST http://localhost:8080/api/jobs/upload \
  -F "file=@sample/Job-profiles/[select-a-pdf-file].pdf"
```

#### 10. Still Having Issues?

If you're still experiencing problems:

1. Check the [GitHub Issues](https://github.com/gajananchalke9/smart-job-hunt/issues) for similar problems
2. Review the complete logs with DEBUG level enabled
3. Verify all prerequisites in the README are met
4. Try the Swagger UI at http://localhost:8080/swagger-ui.html to test endpoints interactively

---

## License

MIT
