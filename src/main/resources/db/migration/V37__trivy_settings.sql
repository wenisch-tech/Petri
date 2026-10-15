-- Instance-wide settings of the vulnerability scanner (Trivy): where its database comes from.
-- A single row; no row means the defaults of the application properties apply.
CREATE TABLE trivy_settings (
    id UUID PRIMARY KEY,
    mode VARCHAR(16) NOT NULL DEFAULT 'ONLINE',
    db_repository VARCHAR(300),
    java_db_repository VARCHAR(300),
    checks_repository VARCHAR(300),
    registry_username VARCHAR(200),
    registry_password_ciphertext VARCHAR(2000),
    insecure BOOLEAN NOT NULL DEFAULT FALSE,
    offline_db_path VARCHAR(300),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(320)
);
