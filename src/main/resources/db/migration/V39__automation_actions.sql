-- What an automation runs is a row, not a Java constant. The built-in actions are written (and kept
-- current) by the application at startup; a custom action is just another row.
CREATE TABLE automation_action (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    skill VARCHAR(64) NOT NULL,
    base_skill VARCHAR(64) NOT NULL,
    comments BOOLEAN NOT NULL DEFAULT FALSE,
    improves BOOLEAN NOT NULL DEFAULT FALSE,
    branch_based BOOLEAN NOT NULL DEFAULT FALSE,
    merges BOOLEAN NOT NULL DEFAULT FALSE,
    vulnerability BOOLEAN NOT NULL DEFAULT FALSE,
    triggers VARCHAR(64) NOT NULL,
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- An action id is longer than the old enum names could be.
ALTER TABLE project_automation ALTER COLUMN kind SET DATA TYPE VARCHAR(64);
ALTER TABLE automation_run ALTER COLUMN kind SET DATA TYPE VARCHAR(64);
