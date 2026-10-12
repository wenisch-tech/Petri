-- Model providers become admin-defined instances. An instance has a kind (provider) that selects the
-- client implementation, so several instances of the same kind can coexist (for example two LiteLLM
-- gateways). owner_id IS NULL marks a global instance; otherwise it is private to that user.
CREATE TABLE model_provider_instance (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    base_url VARCHAR(2048),
    model_name VARCHAR(200) NOT NULL,
    max_output_tokens INTEGER NOT NULL,
    context_window_tokens INTEGER NOT NULL,
    reasoning_effort VARCHAR(16),
    text_verbosity VARCHAR(16),
    shared_api_key_ciphertext TEXT,
    owner_id UUID,
    enabled BOOLEAN NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by UUID NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_model_instance_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    CONSTRAINT fk_model_instance_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_model_instance_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (id)
);

CREATE INDEX idx_model_provider_instance_owner ON model_provider_instance (owner_id);

-- A user's own API key for an instance. It takes precedence over the instance's shared key.
CREATE TABLE model_user_api_key (
    id UUID PRIMARY KEY,
    instance_id UUID NOT NULL,
    user_id UUID NOT NULL,
    api_key_ciphertext TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_model_user_key_instance FOREIGN KEY (instance_id)
        REFERENCES model_provider_instance (id) ON DELETE CASCADE,
    CONSTRAINT fk_model_user_key_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uk_model_user_key UNIQUE (instance_id, user_id)
);

-- Single-row instance policy.
CREATE TABLE model_provider_policy (
    id INTEGER PRIMARY KEY,
    allow_user_providers BOOLEAN NOT NULL,
    updated_by UUID,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO model_provider_policy (id, allow_user_providers, updated_by, updated_at)
VALUES (1, FALSE, NULL, CURRENT_TIMESTAMP);

-- Existing single-per-kind configuration becomes one global instance per kind. The previous key is
-- kept as the instance's shared key so every existing run and workspace keeps working unchanged.
INSERT INTO model_provider_instance (
    id, provider, display_name, base_url, model_name, max_output_tokens, context_window_tokens,
    reasoning_effort, text_verbosity, shared_api_key_ciphertext, owner_id, enabled,
    created_by, created_at, updated_by, updated_at)
SELECT
    CAST(CASE provider
        WHEN 'OPENAI' THEN '00000000-0000-4000-8000-0000000000a1'
        WHEN 'LITELLM' THEN '00000000-0000-4000-8000-0000000000a2'
        WHEN 'OLLAMA' THEN '00000000-0000-4000-8000-0000000000a3'
        ELSE '00000000-0000-4000-8000-0000000000a4'
    END AS UUID),
    provider,
    CASE provider
        WHEN 'OPENAI' THEN 'OpenAI'
        WHEN 'LITELLM' THEN 'LiteLLM'
        WHEN 'OLLAMA' THEN 'Ollama'
        ELSE 'OpenAI-compatible'
    END,
    base_url, model_name, max_output_tokens, context_window_tokens,
    reasoning_effort, text_verbosity, api_key_ciphertext, NULL, TRUE,
    created_by, created_at, updated_by, updated_at
FROM model_provider_credential;

DROP TABLE model_provider_credential;

-- Rows without an instance id resolve to the oldest global instance of their kind, which is what
-- every pre-existing row refers to.
ALTER TABLE agent_run ADD COLUMN provider_instance_id UUID;
ALTER TABLE agent_workflow_role_snapshot ADD COLUMN provider_instance_id UUID;
ALTER TABLE orchestration_profile ADD COLUMN provider_instance_id UUID;
ALTER TABLE workspace_project_preference ADD COLUMN provider_instance_id UUID;
