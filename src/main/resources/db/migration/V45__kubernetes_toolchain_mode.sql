-- Whether the bundle of command line tools Petri ships (git, python, go, curl, node, npm) is copied into
-- the workspace pod: OFF, FALLBACK (after the image's own tools on the PATH) or PREFER (before them).
-- Null in a project row inherits the instance value; without any value FALLBACK applies.
ALTER TABLE kubernetes_workspace_config ADD COLUMN toolchain_mode VARCHAR(16);
