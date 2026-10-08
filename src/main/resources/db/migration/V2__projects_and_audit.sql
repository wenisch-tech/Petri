CREATE TABLE agent_project (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(80) NOT NULL UNIQUE,
    repository_uri VARCHAR(2048) NOT NULL,
    default_branch VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_project_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE INDEX idx_agent_project_created_at ON agent_project (created_at);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_email VARCHAR(320) NOT NULL,
    event_action VARCHAR(80) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id UUID NOT NULL,
    summary VARCHAR(1000) NOT NULL
);

CREATE INDEX idx_audit_event_occurred_at ON audit_event (occurred_at);
CREATE INDEX idx_audit_event_target ON audit_event (target_type, target_id);
