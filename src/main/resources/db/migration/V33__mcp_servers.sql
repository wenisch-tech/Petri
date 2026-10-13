-- MCP servers belong to a user (like Git accounts); a project only activates them.
CREATE TABLE mcp_server (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    transport VARCHAR(24) NOT NULL,
    auth_type VARCHAR(16) NOT NULL,
    secret_ciphertext TEXT,
    oauth_client_id VARCHAR(512),
    oauth_client_secret_ciphertext TEXT,
    oauth_scopes VARCHAR(512),
    oauth_authorization_url VARCHAR(2048),
    oauth_token_url VARCHAR(2048),
    oauth_registration_url VARCHAR(2048),
    oauth_resource VARCHAR(2048),
    oauth_access_ciphertext TEXT,
    oauth_refresh_ciphertext TEXT,
    oauth_expires_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(16) NOT NULL,
    last_error VARCHAR(500),
    tools_json TEXT,
    tools_refreshed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_mcp_server_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    CONSTRAINT uk_mcp_server_name UNIQUE (owner_id, name)
);

CREATE INDEX idx_mcp_server_owner ON mcp_server (owner_id);

-- A project activates servers of its creator; auto_approve skips the per-call approval.
CREATE TABLE project_mcp_server (
    project_id UUID NOT NULL,
    server_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auto_approve BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (project_id, server_id),
    CONSTRAINT fk_project_mcp_project FOREIGN KEY (project_id) REFERENCES agent_project (id),
    CONSTRAINT fk_project_mcp_server FOREIGN KEY (server_id) REFERENCES mcp_server (id) ON DELETE CASCADE
);

-- A tool call that needs a person's approval before it reaches the server.
CREATE TABLE mcp_tool_call (
    id UUID PRIMARY KEY,
    agent_run_id UUID NOT NULL,
    worktree_id UUID NOT NULL,
    server_id UUID NOT NULL,
    server_name VARCHAR(80) NOT NULL,
    tool_name VARCHAR(200) NOT NULL,
    arguments_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_text TEXT,
    error VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_mcp_tool_call_run FOREIGN KEY (agent_run_id) REFERENCES agent_run (id)
);

CREATE INDEX idx_mcp_tool_call_run ON mcp_tool_call (agent_run_id, created_at);
CREATE INDEX idx_mcp_tool_call_status ON mcp_tool_call (status);
