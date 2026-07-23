import json
import tempfile
import unittest
from pathlib import Path

from smart_job_hunt.job_datastore import JobProfileDatastore


class JobProfileDatastoreTests(unittest.TestCase):
    def test_add_profile_and_search_with_scoring(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pdf = root / "backend.pdf"
            resume = root / "resume.txt"
            store = root / "store.json"
            pdf.write_text("fake", encoding="utf-8")
            resume.write_text("Python FastAPI Docker GCP microservices", encoding="utf-8")

            ds = JobProfileDatastore(datastore_path=store)
            ds.add_job_profile(
                job_id="job-1",
                title="Backend Python Engineer",
                pdf_path=pdf,
                description="Python FastAPI Docker PostgreSQL services",
            )
            ds.add_job_profile(
                job_id="job-2",
                title="Frontend React Engineer",
                pdf_path=pdf,
                description="React TypeScript CSS UI",
            )

            results = ds.search_jobs(
                prompt="engineer roles",
                resume_text=resume.read_text(encoding="utf-8"),
                top_k=2,
            )

            self.assertEqual(results[0].job_id, "job-1")
            self.assertGreater(results[0].score, results[1].score)
            self.assertIn("python", results[0].matched_terms)

    def test_add_replaces_same_job_id(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pdf = root / "backend.pdf"
            store = root / "store.json"
            pdf.write_text("fake", encoding="utf-8")

            ds = JobProfileDatastore(datastore_path=store)
            ds.add_job_profile(
                job_id="job-1",
                title="Role A",
                pdf_path=pdf,
                description="one",
            )
            ds.add_job_profile(
                job_id="job-1",
                title="Role B",
                pdf_path=pdf,
                description="two",
            )

            payload = json.loads(store.read_text(encoding="utf-8"))
            self.assertEqual(len(payload["jobs"]), 1)
            self.assertEqual(payload["jobs"][0]["title"], "Role B")


if __name__ == "__main__":
    unittest.main()
