CREATE TABLE workspace_project_preference (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    model_provider VARCHAR(32) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workspace_project_preference_project
        FOREIGN KEY (project_id) REFERENCES agent_project (id),
    CONSTRAINT fk_workspace_project_preference_user
        FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uq_workspace_project_preference_user_project UNIQUE (user_id, project_id)
);

ALTER TABLE agent_chat ADD COLUMN auto_approve_changes BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE agent_chat ADD COLUMN auto_approve_tests BOOLEAN DEFAULT FALSE NOT NULL;
