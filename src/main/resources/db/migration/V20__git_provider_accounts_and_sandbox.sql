CREATE TABLE git_provider_instance (
    id UUID PRIMARY KEY,
    provider_type VARCHAR(24) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    web_base_url VARCHAR(2048) NOT NULL UNIQUE,
    api_base_url VARCHAR(2048) NOT NULL,
    oauth_authorization_url VARCHAR(2048),
    oauth_token_url VARCHAR(2048),
    oauth_scopes VARCHAR(1000),
    oauth_client_id VARCHAR(500),
    oauth_client_secret_ciphertext TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE git_account_connection (
    id UUID PRIMARY KEY,
    provider_instance_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    auth_method VARCHAR(16) NOT NULL,
    external_account_id VARCHAR(255) NOT NULL,
    external_login VARCHAR(320) NOT NULL,
    display_name VARCHAR(320),
    access_token_ciphertext TEXT,
    refresh_token_ciphertext TEXT,
    access_expires_at TIMESTAMP WITH TIME ZONE,
    refresh_expires_at TIMESTAMP WITH TIME ZONE,
    granted_scopes VARCHAR(1000),
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_git_account_provider FOREIGN KEY (provider_instance_id) REFERENCES git_provider_instance (id),
    CONSTRAINT fk_git_account_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    CONSTRAINT uk_git_account_identity UNIQUE (owner_id, provider_instance_id, external_account_id)
);

CREATE INDEX idx_git_account_owner ON git_account_connection (owner_id, status);

CREATE TABLE project_provider_source (
    project_id UUID PRIMARY KEY,
    provider_instance_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    external_repository_id VARCHAR(255) NOT NULL,
    full_name VARCHAR(500) NOT NULL,
    web_url VARCHAR(2048),
    visibility VARCHAR(32),
    can_write BOOLEAN NOT NULL,
    archived BOOLEAN NOT NULL,
    credential_mode VARCHAR(24) NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_project_source_project FOREIGN KEY (project_id) REFERENCES agent_project (id),
    CONSTRAINT fk_project_source_provider FOREIGN KEY (provider_instance_id) REFERENCES git_provider_instance (id),
    CONSTRAINT fk_project_source_connection FOREIGN KEY (connection_id) REFERENCES git_account_connection (id),
    CONSTRAINT fk_project_source_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    CONSTRAINT uk_project_source_external UNIQUE (owner_id, provider_instance_id, external_repository_id)
);

CREATE TABLE project_initialization_job (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(255),
    lease_until TIMESTAMP WITH TIME ZONE,
    error_message VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_project_initialization_project FOREIGN KEY (project_id) REFERENCES agent_project (id)
);

CREATE INDEX idx_project_initialization_status ON project_initialization_job (status, created_at);

CREATE TABLE test_sandbox_profile (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    image VARCHAR(1000) NOT NULL,
    allowed_build_systems VARCHAR(255) NOT NULL,
    cpu_limit VARCHAR(32) NOT NULL,
    memory_limit VARCHAR(32) NOT NULL,
    pids_limit INTEGER NOT NULL,
    timeout_seconds INTEGER NOT NULL,
    output_limit_bytes INTEGER NOT NULL,
    network_enabled BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    default_profile BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE project_sandbox_setting (
    project_id UUID PRIMARY KEY,
    profile_id UUID NOT NULL,
    network_enabled BOOLEAN,
    CONSTRAINT fk_project_sandbox_project FOREIGN KEY (project_id) REFERENCES agent_project (id),
    CONSTRAINT fk_project_sandbox_profile FOREIGN KEY (profile_id) REFERENCES test_sandbox_profile (id)
);

ALTER TABLE test_run ADD COLUMN sandbox_profile_id UUID;
ALTER TABLE test_run ADD COLUMN sandbox_profile_name VARCHAR(120);
ALTER TABLE test_run ADD COLUMN sandbox_image VARCHAR(1000);
ALTER TABLE test_run ADD COLUMN sandbox_image_digest VARCHAR(255);
ALTER TABLE test_run ADD COLUMN sandbox_network_enabled BOOLEAN;

INSERT INTO git_provider_instance (
    id, provider_type, display_name, web_base_url, api_base_url,
    oauth_authorization_url, oauth_token_url, oauth_scopes,
    enabled, built_in, created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000001', 'GITHUB', 'GitHub.com',
    'https://github.com', 'https://api.github.com',
    'https://github.com/login/oauth/authorize', 'https://github.com/login/oauth/access_token',
    'repo read:user', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO git_provider_instance (
    id, provider_type, display_name, web_base_url, api_base_url,
    oauth_authorization_url, oauth_token_url, oauth_scopes,
    enabled, built_in, created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000002', 'GITLAB', 'GitLab.com',
    'https://gitlab.com', 'https://gitlab.com/api/v4',
    'https://gitlab.com/oauth/authorize', 'https://gitlab.com/oauth/token',
    'read_user read_api write_repository', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO test_sandbox_profile (
    id, name, image, allowed_build_systems, cpu_limit, memory_limit,
    pids_limit, timeout_seconds, output_limit_bytes, network_enabled,
    enabled, default_profile, created_at, updated_at
) VALUES (
    '20000000-0000-0000-0000-000000000001', 'Java 25',
    'docker.io/library/eclipse-temurin:25-jdk', 'MAVEN,GRADLE',
    '2', '2g', 256, 600, 2097152, TRUE, TRUE, TRUE,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
