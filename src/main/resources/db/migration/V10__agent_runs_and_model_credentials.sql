CREATE TABLE model_provider_credential (
    provider VARCHAR(32) PRIMARY KEY,
    api_key_ciphertext TEXT NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by UUID NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_model_credential_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_model_credential_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (id)
);

CREATE TABLE agent_run (
    id UUID PRIMARY KEY,
    worktree_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    prompt TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    max_model_turns INTEGER NOT NULL,
    model_turn_count INTEGER NOT NULL,
    next_turn_index INTEGER NOT NULL,
    final_output TEXT,
    error_message VARCHAR(1000),
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_agent_run_worktree FOREIGN KEY (worktree_id) REFERENCES project_worktree (id),
    CONSTRAINT fk_agent_run_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE INDEX idx_agent_run_worktree_created
    ON agent_run (worktree_id, created_at);

CREATE TABLE agent_turn (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    turn_index INTEGER NOT NULL,
    turn_kind VARCHAR(32) NOT NULL,
    provider_response_id VARCHAR(200),
    tool_call_id VARCHAR(200),
    tool_name VARCHAR(80),
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_turn_run FOREIGN KEY (run_id) REFERENCES agent_run (id),
    CONSTRAINT uq_agent_turn_index UNIQUE (run_id, turn_index)
);

CREATE INDEX idx_agent_turn_run_index
    ON agent_turn (run_id, turn_index);
