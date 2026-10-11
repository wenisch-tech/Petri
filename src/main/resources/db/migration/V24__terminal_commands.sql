CREATE TABLE terminal_command (
    id UUID PRIMARY KEY,
    worktree_id UUID NOT NULL,
    agent_run_id UUID,
    source VARCHAR(16) NOT NULL,
    command VARCHAR(4000) NOT NULL,
    reason VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    exit_code INTEGER,
    output TEXT,
    output_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    requested_by UUID,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_by UUID,
    approved_at TIMESTAMP WITH TIME ZONE,
    rejected_by UUID,
    rejected_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_terminal_command_worktree FOREIGN KEY (worktree_id) REFERENCES project_worktree (id)
);

CREATE INDEX idx_terminal_command_run ON terminal_command (agent_run_id, requested_at);
CREATE INDEX idx_terminal_command_worktree ON terminal_command (worktree_id, requested_at);

ALTER TABLE project_sandbox_setting
    ADD COLUMN auto_approve_terminal_commands BOOLEAN NOT NULL DEFAULT FALSE;
