ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE user_identity (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider_registration_id VARCHAR(100) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_user_identity_provider_subject UNIQUE (provider_registration_id, subject),
    CONSTRAINT fk_user_identity_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE INDEX idx_user_identity_user ON user_identity (user_id);
