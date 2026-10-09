ALTER TABLE change_proposal ALTER COLUMN expected_sha256 DROP NOT NULL;
ALTER TABLE change_proposal ADD COLUMN change_type VARCHAR(16) NOT NULL DEFAULT 'REPLACE';
UPDATE change_proposal SET change_type = 'REPLACE' WHERE change_type IS NULL;