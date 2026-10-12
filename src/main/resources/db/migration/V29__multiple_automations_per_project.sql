-- A project may run the same skill from several triggers (for example on pull requests and on a
-- schedule); each combination exists once.
ALTER TABLE project_automation DROP CONSTRAINT uk_project_automation;
ALTER TABLE project_automation
    ADD CONSTRAINT uk_project_automation_trigger UNIQUE (project_id, kind, trigger_type);
