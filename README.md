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
gcloud storage buckets create gs://YOUR_BUCKET_NAME \
  --project=YOUR_PROJECT_ID \
  --location=us-central1
```

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
    bucket: YOUR_GCS_BUCKET_NAME
  discovery-engine:
    datastore-id: YOUR_DATASTORE_ID
  vertex-ai:
    gemini-model: gemini-1.5-flash          # or gemini-1.5-pro
```

Alternatively, override any property via environment variable using Spring's relaxed binding, e.g.:

```bash
export GCP_PROJECT_ID=my-project
export GCP_GCS_BUCKET=my-bucket
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
  "gcsUri": "gs://your-bucket/jobs/job-description.jsonl",
  "message": "Job uploaded and indexed successfully with metadata."
}
```

**How it works:**

1. The PDF is uploaded to GCS with a unique UUID-based filename (e.g., `gs://bucket/jobs/550e8400-e29b-41d4-a716-446655440000.pdf`)
2. If metadata is not provided, the system extracts it from the PDF content using AI
3. A JSONL metadata file is created with the structured data:
   ```json
   {
     "id": "550e8400-e29b-41d4-a716-446655440000",
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
       "uri": "gs://bucket/jobs/job-description.pdf"
     }
   }
   ```
4. The JSONL file is uploaded to GCS (e.g., `gs://bucket/jobs/550e8400-e29b-41d4-a716-446655440000.jsonl`)
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
    "gcsUri": "gs://your-bucket/jobs/apprentice-hiring.pdf",
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
    "gcsUri": "gs://your-bucket/jobs/senior-backend.pdf",
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
    "gcsUri": "gs://your-bucket/jobs/devops.pdf",
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
│   └── GcpConfig.java               # GCP client Spring beans (Storage, DiscoveryEngine, VertexAI)
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

## License

MIT
