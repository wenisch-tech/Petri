CREATE TABLE package_install_request (
    id UUID PRIMARY KEY,
    worktree_id UUID NOT NULL,
    agent_run_id UUID,
    packages VARCHAR(1500) NOT NULL,
    reason VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    exit_code INTEGER,
    output TEXT,
    output_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_by UUID,
    approved_at TIMESTAMP WITH TIME ZONE,
    rejected_by UUID,
    rejected_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_package_install_worktree FOREIGN KEY (worktree_id) REFERENCES project_worktree (id),
    CONSTRAINT fk_package_install_approved_by FOREIGN KEY (approved_by) REFERENCES app_user (id),
    CONSTRAINT fk_package_install_rejected_by FOREIGN KEY (rejected_by) REFERENCES app_user (id)
);

CREATE INDEX idx_package_install_run ON package_install_request (agent_run_id, requested_at);
CREATE INDEX idx_package_install_status ON package_install_request (status);

ALTER TABLE project_sandbox_setting
    ADD COLUMN auto_approve_package_installs BOOLEAN NOT NULL DEFAULT FALSE;
