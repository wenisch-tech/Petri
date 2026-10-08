CREATE TABLE api_key (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    key_identifier VARCHAR(32) NOT NULL UNIQUE,
    secret_hash VARCHAR(200) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    revoked_by UUID,
    CONSTRAINT fk_api_key_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_api_key_revoked_by FOREIGN KEY (revoked_by) REFERENCES app_user (id)
);

CREATE INDEX idx_api_key_identifier ON api_key (key_identifier);
CREATE INDEX idx_api_key_created_at ON api_key (created_at);
