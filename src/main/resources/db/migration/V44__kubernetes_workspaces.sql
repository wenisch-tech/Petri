-- Kubernetes workspaces: a project's mirror, worktrees and commands can live in a pod on a cluster.
-- One row per scope: the single instance row (project_id IS NULL) holds the defaults, a project row
-- holds the project's switch and overrides. A null column in a project row inherits the instance value.
CREATE TABLE kubernetes_workspace_config (
    id UUID PRIMARY KEY,
    project_id UUID UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    cluster_override BOOLEAN NOT NULL DEFAULT FALSE,
    auth_mode VARCHAR(16),
    kubeconfig_ciphertext TEXT,
    api_server_url VARCHAR(500),
    token_ciphertext TEXT,
    ca_pem TEXT,
    insecure BOOLEAN NOT NULL DEFAULT FALSE,
    namespace VARCHAR(63),
    image VARCHAR(500),
    pvc_size VARCHAR(32),
    storage_class VARCHAR(253),
    cpu_request VARCHAR(32),
    cpu_limit VARCHAR(32),
    memory_request VARCHAR(32),
    memory_limit VARCHAR(32),
    run_as_user BIGINT,
    fs_group BIGINT,
    idle_timeout_minutes INT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(320)
);

-- What Petri believes about a project's workspace pod. The cluster is the source of truth and is
-- reconciled with this table on start-up and whenever the pod is needed.
CREATE TABLE workspace_pod_state (
    project_id UUID PRIMARY KEY,
    namespace VARCHAR(63) NOT NULL,
    pod_name VARCHAR(63) NOT NULL,
    pvc_name VARCHAR(63) NOT NULL,
    secret_name VARCHAR(63) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    agent_token_ciphertext VARCHAR(2000),
    agent_arch VARCHAR(16),
    image VARCHAR(500),
    image_digest VARCHAR(300),
    node_name VARCHAR(253),
    started_at TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(1000),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
