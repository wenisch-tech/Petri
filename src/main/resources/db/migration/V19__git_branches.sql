ALTER TABLE project_worktree ADD COLUMN branch_name VARCHAR(255);
ALTER TABLE project_worktree ADD COLUMN upstream_branch VARCHAR(255);

CREATE INDEX idx_project_worktree_project_branch
    ON project_worktree (project_id, branch_name, status);
