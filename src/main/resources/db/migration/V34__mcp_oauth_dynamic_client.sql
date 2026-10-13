-- Remembers that Petri registered the OAuth client itself (RFC 7591), so it can register again
-- when the redirect address changes. A client id the user entered is never replaced.
ALTER TABLE mcp_server ADD COLUMN oauth_dynamic_client BOOLEAN NOT NULL DEFAULT FALSE;
