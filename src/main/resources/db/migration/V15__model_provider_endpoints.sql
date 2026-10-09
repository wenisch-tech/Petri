ALTER TABLE model_provider_credential
    ADD COLUMN base_url VARCHAR(2048);

ALTER TABLE model_provider_credential
    ALTER COLUMN api_key_ciphertext DROP NOT NULL;
