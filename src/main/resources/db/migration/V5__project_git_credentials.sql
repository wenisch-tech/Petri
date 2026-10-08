CREATE TABLE project_git_credential (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL UNIQUE,
    authentication_type VARCHAR(32) NOT NULL,
    username VARCHAR(320),
    secret_ciphertext VARCHAR(16384) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by UUID NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_project_git_credential_project FOREIGN KEY (project_id) REFERENCES agent_project (id),
    CONSTRAINT fk_project_git_credential_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_project_git_credential_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (id)
);

CREATE INDEX idx_project_git_credential_project ON project_git_credential (project_id);
