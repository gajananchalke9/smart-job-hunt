from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


WORD_RE = re.compile(r"[a-zA-Z0-9_+#.-]+")


@dataclass
class JobMatch:
    job_id: str
    title: str
    score: float
    matched_terms: list[str]
    summary: str
    gcs_uri: str | None = None


class JobProfileDatastore:
    """Lightweight datastore for job profile PDFs with optional GCP integrations."""

    def __init__(
        self,
        datastore_path: str | Path = "data/job_profiles.json",
        gcs_bucket: str | None = None,
        search_client: Any | None = None,
        storage_client: Any | None = None,
    ) -> None:
        self.datastore_path = Path(datastore_path)
        self.gcs_bucket = gcs_bucket
        self.search_client = search_client
        self.storage_client = storage_client
        self.datastore_path.parent.mkdir(parents=True, exist_ok=True)
        if not self.datastore_path.exists():
            self._write_store({"jobs": []})

    def add_job_profile(
        self,
        job_id: str,
        title: str,
        pdf_path: str | Path,
        description: str,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        pdf_file = Path(pdf_path)
        if not pdf_file.exists():
            raise FileNotFoundError(f"Job profile PDF not found: {pdf_file}")

        gcs_uri = self._upload_to_gcs(pdf_file)
        entry = {
            "job_id": job_id,
            "title": title,
            "description": description,
            "metadata": metadata or {},
            "pdf_path": str(pdf_file),
            "gcs_uri": gcs_uri,
        }

        store = self._read_store()
        store["jobs"] = [j for j in store["jobs"] if j["job_id"] != job_id]
        store["jobs"].append(entry)
        self._write_store(store)

        self._index_job_profile(entry)
        return entry

    def search_jobs(
        self,
        prompt: str,
        resume_text: str,
        top_k: int = 5,
    ) -> list[JobMatch]:
        candidates = self._search_with_vertex(prompt, top_k) or self._keyword_search(prompt)
        results = [self._score_job(c, resume_text) for c in candidates]
        results.sort(key=lambda m: m.score, reverse=True)
        return results[:top_k]

    def _search_with_vertex(self, prompt: str, top_k: int) -> list[dict[str, Any]]:
        if not self.search_client:
            return []

        try:
            response = self.search_client.search(prompt=prompt, top_k=top_k)
        except Exception:
            return []

        ids = {
            item.get("job_id")
            for item in response
            if isinstance(item, dict) and item.get("job_id")
        }
        if not ids:
            return []

        store = self._read_store()
        return [job for job in store["jobs"] if job["job_id"] in ids]

    def _keyword_search(self, prompt: str) -> list[dict[str, Any]]:
        prompt_terms = set(_tokenize(prompt))
        if not prompt_terms:
            return self._read_store()["jobs"]

        ranked: list[tuple[int, dict[str, Any]]] = []
        for job in self._read_store()["jobs"]:
            text = f"{job['title']} {job['description']}"
            terms = set(_tokenize(text))
            overlap = len(prompt_terms & terms)
            ranked.append((overlap, job))
        ranked.sort(key=lambda item: item[0], reverse=True)
        return [job for overlap, job in ranked if overlap > 0] or self._read_store()["jobs"]

    def _score_job(self, job: dict[str, Any], resume_text: str) -> JobMatch:
        resume_terms = set(_tokenize(resume_text))
        job_terms = set(_tokenize(f"{job['title']} {job['description']}"))
        matched = sorted(resume_terms & job_terms)
        score = 0.0 if not job_terms else round((len(matched) / len(job_terms)) * 100, 2)
        summary = (
            f"Matched {len(matched)} job terms out of {len(job_terms)} "
            f"({score:.2f}% relevance)."
        )
        return JobMatch(
            job_id=job["job_id"],
            title=job["title"],
            score=score,
            matched_terms=matched,
            summary=summary,
            gcs_uri=job.get("gcs_uri"),
        )

    def _upload_to_gcs(self, pdf_file: Path) -> str | None:
        if not self.gcs_bucket or not self.storage_client:
            return None

        blob_name = f"job-profiles/{pdf_file.name}"
        bucket = self.storage_client.bucket(self.gcs_bucket)
        blob = bucket.blob(blob_name)
        blob.upload_from_filename(str(pdf_file))
        return f"gs://{self.gcs_bucket}/{blob_name}"

    def _index_job_profile(self, entry: dict[str, Any]) -> None:
        if not self.search_client:
            return

        try:
            self.search_client.index_document(
                {
                    "job_id": entry["job_id"],
                    "title": entry["title"],
                    "description": entry["description"],
                    "gcs_uri": entry.get("gcs_uri"),
                }
            )
        except Exception:
            return

    def _read_store(self) -> dict[str, Any]:
        with self.datastore_path.open("r", encoding="utf-8") as fh:
            return json.load(fh)

    def _write_store(self, payload: dict[str, Any]) -> None:
        with self.datastore_path.open("w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2)


class VertexAISearchClient:
    """Thin wrapper for Vertex AI Search/Discovery Engine integrations."""

    def __init__(self, project_id: str, location: str, data_store_id: str) -> None:
        self.project_id = project_id
        self.location = location
        self.data_store_id = data_store_id

    def index_document(self, payload: dict[str, Any]) -> None:
        # Hook point for discoveryengine.DocumentServiceClient import and upsert.
        _ = payload

    def search(self, prompt: str, top_k: int) -> list[dict[str, Any]]:
        # Hook point for discoveryengine.SearchServiceClient query.
        _ = (prompt, top_k)
        return []


def _tokenize(text: str) -> list[str]:
    return [t.lower() for t in WORD_RE.findall(text)]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Job profile datastore with scoring")
    sub = parser.add_subparsers(dest="command", required=True)

    add_p = sub.add_parser("add", help="Add a job profile")
    add_p.add_argument("--job-id", required=True)
    add_p.add_argument("--title", required=True)
    add_p.add_argument("--pdf", required=True)
    add_p.add_argument("--description", required=True)

    search_p = sub.add_parser("search", help="Search and score job profiles")
    search_p.add_argument("--prompt", required=True)
    search_p.add_argument("--resume", required=True)
    search_p.add_argument("--top-k", type=int, default=5)

    parser.add_argument("--datastore", default="data/job_profiles.json")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    datastore = JobProfileDatastore(datastore_path=args.datastore)

    if args.command == "add":
        added = datastore.add_job_profile(
            job_id=args.job_id,
            title=args.title,
            pdf_path=args.pdf,
            description=args.description,
        )
        print(json.dumps(added, indent=2))
        return

    if args.command == "search":
        resume_text = Path(args.resume).read_text(encoding="utf-8")
        results = datastore.search_jobs(
            prompt=args.prompt,
            resume_text=resume_text,
            top_k=args.top_k,
        )
        print(
            json.dumps(
                [
                    {
                        "job_id": item.job_id,
                        "title": item.title,
                        "score": item.score,
                        "matched_terms": item.matched_terms,
                        "summary": item.summary,
                        "gcs_uri": item.gcs_uri,
                    }
                    for item in results
                ],
                indent=2,
            )
        )


if __name__ == "__main__":
    main()
