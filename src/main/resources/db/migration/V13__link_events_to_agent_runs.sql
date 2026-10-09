ALTER TABLE agent_run_event ADD COLUMN agent_run_id UUID;
ALTER TABLE agent_run_event ADD CONSTRAINT fk_agent_run_event_agent_run
    FOREIGN KEY (agent_run_id) REFERENCES agent_run (id);
CREATE INDEX idx_agent_run_event_run_occurred
    ON agent_run_event (agent_run_id, occurred_at);

