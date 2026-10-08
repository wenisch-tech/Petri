CREATE TABLE agent_run_event (
    id UUID PRIMARY KEY,
    worktree_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    tool_name VARCHAR(80),
    summary VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_run_event_worktree FOREIGN KEY (worktree_id) REFERENCES project_worktree (id)
);

CREATE INDEX idx_agent_run_event_worktree_occurred
    ON agent_run_event (worktree_id, occurred_at);
