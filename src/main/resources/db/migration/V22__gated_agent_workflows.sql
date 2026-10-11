ALTER TABLE agent_chat ADD COLUMN execution_mode VARCHAR(24) DEFAULT 'DIRECT' NOT NULL;
ALTER TABLE agent_chat ADD COLUMN parent_chat_id UUID;
ALTER TABLE agent_chat ADD COLUMN forked_from_workflow_id UUID;
ALTER TABLE agent_chat ADD CONSTRAINT fk_agent_chat_parent
    FOREIGN KEY (parent_chat_id) REFERENCES agent_chat (id);

ALTER TABLE agent_run ALTER COLUMN chat_message_index DROP NOT NULL;

CREATE TABLE orchestration_profile (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    model_provider VARCHAR(32) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    max_model_turns INTEGER NOT NULL,
    max_output_tokens INTEGER NOT NULL,
    context_window_tokens INTEGER NOT NULL,
    reasoning_effort VARCHAR(16),
    text_verbosity VARCHAR(16),
    max_attempts INTEGER NOT NULL DEFAULT 3,
    prompt_addendum TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_orchestration_profile_project FOREIGN KEY (project_id) REFERENCES agent_project (id),
    CONSTRAINT fk_orchestration_profile_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uq_orchestration_profile_role UNIQUE (project_id, user_id, role)
);

CREATE TABLE agent_workflow (
    id UUID PRIMARY KEY,
    chat_id UUID NOT NULL,
    chat_message_index INTEGER NOT NULL,
    execution_worktree_id UUID NOT NULL,
    prompt TEXT NOT NULL,
    status VARCHAR(48) NOT NULL,
    current_stage VARCHAR(48) NOT NULL,
    baseline_head VARCHAR(64),
    baseline_tree VARCHAR(64),
    current_plan_revision INTEGER,
    approved_plan_revision INTEGER,
    hold_reason VARCHAR(2000),
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_agent_workflow_chat FOREIGN KEY (chat_id) REFERENCES agent_chat (id),
    CONSTRAINT fk_agent_workflow_worktree FOREIGN KEY (execution_worktree_id) REFERENCES project_worktree (id),
    CONSTRAINT fk_agent_workflow_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT uq_agent_workflow_chat_message UNIQUE (chat_id, chat_message_index)
);

ALTER TABLE agent_chat ADD CONSTRAINT fk_agent_chat_fork_workflow
    FOREIGN KEY (forked_from_workflow_id) REFERENCES agent_workflow (id);

ALTER TABLE agent_run ADD COLUMN workflow_id UUID;
ALTER TABLE agent_run ADD COLUMN workflow_role VARCHAR(32);
ALTER TABLE agent_run ADD COLUMN workflow_phase VARCHAR(48);
ALTER TABLE agent_run ADD COLUMN workflow_attempt INTEGER;
ALTER TABLE agent_run ADD COLUMN instructions_snapshot TEXT;
ALTER TABLE agent_run ADD CONSTRAINT fk_agent_run_workflow
    FOREIGN KEY (workflow_id) REFERENCES agent_workflow (id);
CREATE INDEX idx_agent_run_workflow ON agent_run (workflow_id, created_at);

CREATE TABLE agent_workflow_role_snapshot (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    model_provider VARCHAR(32) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    max_model_turns INTEGER NOT NULL,
    max_output_tokens INTEGER NOT NULL,
    context_window_tokens INTEGER NOT NULL,
    reasoning_effort VARCHAR(16),
    text_verbosity VARCHAR(16),
    max_attempts INTEGER NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    prompt_addendum TEXT,
    CONSTRAINT fk_workflow_role_snapshot FOREIGN KEY (workflow_id) REFERENCES agent_workflow (id),
    CONSTRAINT uq_workflow_role_snapshot UNIQUE (workflow_id, role)
);

CREATE TABLE agent_workflow_artifact (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL,
    agent_run_id UUID,
    kind VARCHAR(48) NOT NULL,
    revision INTEGER NOT NULL,
    content_json TEXT NOT NULL,
    display_markdown TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workflow_artifact_workflow FOREIGN KEY (workflow_id) REFERENCES agent_workflow (id),
    CONSTRAINT fk_workflow_artifact_run FOREIGN KEY (agent_run_id) REFERENCES agent_run (id),
    CONSTRAINT uq_workflow_artifact_run UNIQUE (agent_run_id),
    CONSTRAINT uq_workflow_artifact_revision UNIQUE (workflow_id, kind, revision)
);
CREATE INDEX idx_workflow_artifact_workflow ON agent_workflow_artifact (workflow_id, created_at);

CREATE TABLE agent_workflow_transition (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL,
    from_stage VARCHAR(48),
    to_stage VARCHAR(48) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    actor VARCHAR(320) NOT NULL,
    reason VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workflow_transition_workflow FOREIGN KEY (workflow_id) REFERENCES agent_workflow (id)
);
CREATE INDEX idx_workflow_transition_workflow ON agent_workflow_transition (workflow_id, created_at);

CREATE TABLE worktree_execution_reservation (
    worktree_id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL UNIQUE,
    reserved_by UUID NOT NULL,
    reserved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_worktree_reservation_worktree FOREIGN KEY (worktree_id) REFERENCES project_worktree (id),
    CONSTRAINT fk_worktree_reservation_workflow FOREIGN KEY (workflow_id) REFERENCES agent_workflow (id),
    CONSTRAINT fk_worktree_reservation_user FOREIGN KEY (reserved_by) REFERENCES app_user (id)
);
