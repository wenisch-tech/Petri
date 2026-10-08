CREATE TABLE change_proposal (
    id UUID PRIMARY KEY,
    worktree_id UUID NOT NULL,
    file_path VARCHAR(2048) NOT NULL,
    expected_sha256 VARCHAR(64) NOT NULL,
    replacement_sha256 VARCHAR(64) NOT NULL,
    replacement_content TEXT NOT NULL,
    unified_diff TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_by UUID,
    approved_at TIMESTAMP WITH TIME ZONE,
    applied_at TIMESTAMP WITH TIME ZONE,
    rejected_by UUID,
    rejected_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_change_proposal_worktree FOREIGN KEY (worktree_id) REFERENCES project_worktree (id),
    CONSTRAINT fk_change_proposal_approved_by FOREIGN KEY (approved_by) REFERENCES app_user (id),
    CONSTRAINT fk_change_proposal_rejected_by FOREIGN KEY (rejected_by) REFERENCES app_user (id)
);

CREATE INDEX idx_change_proposal_worktree_created
    ON change_proposal (worktree_id, created_at);
