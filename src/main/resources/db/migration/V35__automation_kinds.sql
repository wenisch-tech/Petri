-- "Code review" posted a comment, so existing automations and their runs become "Code review and
-- comment". The plain CODE_REVIEW kind now reviews without publishing.
UPDATE project_automation SET kind = 'CODE_REVIEW_AND_COMMENT' WHERE kind = 'CODE_REVIEW';
UPDATE automation_run SET kind = 'CODE_REVIEW_AND_COMMENT' WHERE kind = 'CODE_REVIEW';

ALTER TABLE automation_run ADD COLUMN note VARCHAR(1000);
ALTER TABLE automation_run ADD COLUMN improve_workflow_id UUID;
ALTER TABLE automation_run ADD COLUMN improve_worktree_id UUID;
