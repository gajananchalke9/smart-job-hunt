# smart-job-hunt

Python datastore for job profile PDFs with search and resume-based scoring.

## Features
- Store job profile PDF metadata in a local JSON datastore
- Optional upload of job profile PDFs to Google Cloud Storage (GCS)
- Optional Vertex AI Search integration hook for prompt-based retrieval
- Score job profiles against a resume using term-overlap relevance scoring

## Usage

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

## Tests
```bash
python -m unittest discover -s tests -p 'test_*.py'
```
