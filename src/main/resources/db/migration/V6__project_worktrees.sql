CREATE TABLE project_worktree (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    git_revision VARCHAR(255) NOT NULL,
    relative_path VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    removed_by UUID,
    removed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_project_worktree_project FOREIGN KEY (project_id) REFERENCES agent_project (id),
    CONSTRAINT fk_project_worktree_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_project_worktree_removed_by FOREIGN KEY (removed_by) REFERENCES app_user (id)
);

CREATE INDEX idx_project_worktree_project_status ON project_worktree (project_id, status);
