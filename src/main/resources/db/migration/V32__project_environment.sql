-- Variables a project adds to its sandbox commands, encrypted like the profile's own. They are
-- applied on top of the sandbox profile's variables.
ALTER TABLE project_sandbox_setting ADD COLUMN environment_ciphertext TEXT;
