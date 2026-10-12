ALTER TABLE agent_run ADD COLUMN loop_guard_nudges INT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN loop_guard_withdrawals INT NOT NULL DEFAULT 0;
