CREATE TABLE test_run (
    id UUID PRIMARY KEY,
    worktree_id UUID NOT NULL,
    build_system VARCHAR(16) NOT NULL,
    command_display VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL,
    exit_code INTEGER,
    output TEXT,
    output_truncated BOOLEAN NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_by UUID,
    approved_at TIMESTAMP WITH TIME ZONE,
    rejected_by UUID,
    rejected_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_test_run_worktree FOREIGN KEY (worktree_id) REFERENCES project_worktree (id),
    CONSTRAINT fk_test_run_approved_by FOREIGN KEY (approved_by) REFERENCES app_user (id),
    CONSTRAINT fk_test_run_rejected_by FOREIGN KEY (rejected_by) REFERENCES app_user (id)
);

CREATE INDEX idx_test_run_worktree_requested
    ON test_run (worktree_id, requested_at);
