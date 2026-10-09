ALTER TABLE agent_run ADD COLUMN execution_claim_id UUID;
ALTER TABLE agent_run ADD COLUMN execution_claimed_at TIMESTAMP WITH TIME ZONE;

