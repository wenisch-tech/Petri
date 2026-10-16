-- Instance-wide MCP servers: an administrator owns them (owner_id IS NULL). Users can switch them
-- on per project; when the administrator stores no credential, every user adds their own.
ALTER TABLE mcp_server ALTER COLUMN owner_id DROP NOT NULL;

CREATE TABLE mcp_user_credential (
    server_id UUID NOT NULL,
    user_id UUID NOT NULL,
    secret_ciphertext TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (server_id, user_id),
    CONSTRAINT fk_mcp_user_credential_server FOREIGN KEY (server_id) REFERENCES mcp_server (id) ON DELETE CASCADE,
    CONSTRAINT fk_mcp_user_credential_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

-- Whose credential an approved call uses (the user the run acts for).
ALTER TABLE mcp_tool_call ADD COLUMN user_id UUID;
