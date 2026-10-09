ALTER TABLE change_proposal ADD COLUMN agent_run_id UUID;
ALTER TABLE change_proposal ADD CONSTRAINT fk_change_proposal_agent_run
    FOREIGN KEY (agent_run_id) REFERENCES agent_run (id);
CREATE INDEX idx_change_proposal_agent_run ON change_proposal (agent_run_id, created_at);

ALTER TABLE test_run ADD COLUMN agent_run_id UUID;
ALTER TABLE test_run ADD CONSTRAINT fk_test_run_agent_run
    FOREIGN KEY (agent_run_id) REFERENCES agent_run (id);
CREATE INDEX idx_test_run_agent_run ON test_run (agent_run_id, requested_at);

