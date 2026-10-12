CREATE TABLE project_automation (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    trigger_type VARCHAR(32) NOT NULL,
    cron_expression VARCHAR(120),
    post_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    provider_instance_id UUID,
    model VARCHAR(200),
    max_model_turns INT NOT NULL DEFAULT 20,
    last_polled_at TIMESTAMP WITH TIME ZONE,
    last_cron_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_project_automation_project FOREIGN KEY (project_id) REFERENCES agent_project (id),
    CONSTRAINT uk_project_automation UNIQUE (project_id, kind)
);

CREATE TABLE automation_run (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    external_ref VARCHAR(64) NOT NULL,
    head_sha VARCHAR(64) NOT NULL,
    title VARCHAR(500),
    web_url VARCHAR(2048),
    status VARCHAR(24) NOT NULL,
    agent_run_id UUID,
    worktree_id UUID,
    changed_files TEXT,
    review_markdown TEXT,
    comment_url VARCHAR(2048),
    error VARCHAR(1000),
    post_mode VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_automation_run_project FOREIGN KEY (project_id) REFERENCES agent_project (id),
    CONSTRAINT uk_automation_run_target UNIQUE (project_id, kind, external_ref, head_sha)
);

CREATE INDEX idx_automation_run_project ON automation_run (project_id, created_at);
CREATE INDEX idx_automation_run_status ON automation_run (status);

ALTER TABLE agent_run ADD COLUMN automation_run_id UUID;
