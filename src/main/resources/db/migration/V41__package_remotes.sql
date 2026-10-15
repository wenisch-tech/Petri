-- Package remotes (mirrors/registries) per ecosystem: instance defaults and per-project overrides.
CREATE TABLE package_remote (
    id UUID PRIMARY KEY,
    project_id UUID,
    ecosystem VARCHAR(16) NOT NULL,
    name VARCHAR(120) NOT NULL,
    url VARCHAR(500) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    username VARCHAR(200),
    password_ciphertext VARCHAR(2000),
    mirror_of VARCHAR(120),
    insecure BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    position INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(320)
);
CREATE INDEX idx_package_remote_project ON package_remote (project_id);

-- Single row: the extra CA bundle (public certificates, PEM) and the "ignore TLS errors" switch.
CREATE TABLE package_tls_settings (
    id UUID PRIMARY KEY,
    ca_pem TEXT,
    ignore_tls_errors BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(320)
);
