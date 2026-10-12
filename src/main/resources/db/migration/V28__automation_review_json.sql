ALTER TABLE automation_run ADD COLUMN review_json TEXT;

CREATE INDEX idx_automation_run_pull_request ON automation_run (project_id, kind, external_ref, created_at);
