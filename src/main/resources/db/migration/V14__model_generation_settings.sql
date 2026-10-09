ALTER TABLE model_provider_credential
    ADD COLUMN max_output_tokens INTEGER DEFAULT 4096 NOT NULL;

ALTER TABLE model_provider_credential
    ADD COLUMN reasoning_effort VARCHAR(16);

ALTER TABLE model_provider_credential
    ADD COLUMN text_verbosity VARCHAR(16);

ALTER TABLE agent_run
    ADD COLUMN max_output_tokens INTEGER DEFAULT 4096 NOT NULL;

ALTER TABLE agent_run
    ADD COLUMN reasoning_effort VARCHAR(16);

ALTER TABLE agent_run
    ADD COLUMN text_verbosity VARCHAR(16);
