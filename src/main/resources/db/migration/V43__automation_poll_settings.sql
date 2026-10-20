-- Instance-wide automation settings; the single row holds how often pull requests are checked.
CREATE TABLE automation_poll_settings (
    id UUID PRIMARY KEY,
    poll_interval_minutes INT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(320)
);
