CREATE TABLE agent_chat (
    id UUID PRIMARY KEY,
    worktree_id UUID NOT NULL,
    title VARCHAR(160) NOT NULL,
    queue_paused BOOLEAN DEFAULT FALSE NOT NULL,
    approval_gate_overridden BOOLEAN DEFAULT FALSE NOT NULL,
    next_message_index INTEGER DEFAULT 0 NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_chat_worktree FOREIGN KEY (worktree_id) REFERENCES project_worktree (id),
    CONSTRAINT fk_agent_chat_created_by FOREIGN KEY (created_by) REFERENCES app_user (id)
);

ALTER TABLE agent_run ADD COLUMN chat_id UUID;
ALTER TABLE agent_run ADD COLUMN chat_message_index INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE agent_run ADD COLUMN context_window_tokens INTEGER DEFAULT 32768 NOT NULL;
ALTER TABLE agent_run ADD COLUMN partial_output TEXT;

INSERT INTO agent_chat (
    id, worktree_id, title, queue_paused, approval_gate_overridden, next_message_index,
    created_by, created_at, updated_at
)
SELECT
    id,
    worktree_id,
    SUBSTRING(prompt, 1, 160),
    FALSE,
    FALSE,
    1,
    created_by,
    created_at,
    COALESCE(completed_at, started_at, created_at)
FROM agent_run;

UPDATE agent_run SET chat_id = id;

ALTER TABLE agent_run ALTER COLUMN chat_id SET NOT NULL;
ALTER TABLE agent_run ADD CONSTRAINT fk_agent_run_chat FOREIGN KEY (chat_id) REFERENCES agent_chat (id);
CREATE UNIQUE INDEX uq_agent_run_chat_message ON agent_run (chat_id, chat_message_index);
CREATE INDEX idx_agent_run_chat_created ON agent_run (chat_id, chat_message_index);

CREATE TABLE agent_chat_summary (
    id UUID PRIMARY KEY,
    chat_id UUID NOT NULL,
    from_message_index INTEGER NOT NULL,
    through_message_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_chat_summary_chat FOREIGN KEY (chat_id) REFERENCES agent_chat (id),
    CONSTRAINT chk_agent_chat_summary_range CHECK (from_message_index <= through_message_index)
);

CREATE INDEX idx_agent_chat_summary_chat_range
    ON agent_chat_summary (chat_id, through_message_index DESC);

ALTER TABLE model_provider_credential
    ADD COLUMN context_window_tokens INTEGER DEFAULT 32768 NOT NULL;
