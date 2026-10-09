# Security model

## Trust boundaries

Petri separates three roles:

- The **model** may request only registered tools.
- An **API client** may create worktrees/runs and invoke tools but cannot approve privileged proposals.
- An **instance administrator** controls credentials and approves file changes or test execution.

Model text and skill instructions are untrusted input. They cannot bypass Java-side path validation, tool registration or approval state transitions.

## Secrets

- Git and model secrets are encrypted with AES-256-GCM before persistence.
- The master key comes from an explicit environment value or an owner-only filesystem file.
- Secrets are not returned by status APIs or rendered into HTML.
- Git secrets are supplied with a temporary askpass process rather than command arguments.
- Model keys are added to the `Authorization` header only for the configured provider request.

Back up the master key securely. Database backup without the matching key cannot restore encrypted credentials.

## Filesystem controls

- Workspace paths are derived beneath one configured root.
- Existing paths are normalized and checked against their real path.
- Symlink escapes, traversal, `.git` internals and oversized files are rejected.
- Worktree deletion validates the persisted relative path against the expected UUID-derived path.

These checks constrain Petri's own operations; they are not a replacement for OS isolation.

## External processes

Git and approved test commands run without a shell, with bounded output and timeouts. Git global/system configuration is disabled and interactive prompting is off. Test execution still runs repository-controlled build logic, so use a dedicated low-privilege account, container or stronger sandbox for untrusted projects.

## Network controls

Model base URLs may intentionally target local containers or LAN services. Petri validates URL structure but does not provide network egress isolation. Restrict the Petri process or container network when administrators must not be able to configure internal destinations.

Use HTTPS whenever bearer credentials cross an untrusted network. Plain HTTP is intended only for trusted local services such as Ollama.

## Production checklist

- Replace the default administrator password.
- Use PostgreSQL and protected backups.
- Persist and protect the master key separately from ephemeral containers.
- Run as a dedicated unprivileged user.
- Restrict workspace permissions and network egress.
- Put Petri behind TLS and a trusted reverse proxy.
- Configure OIDC for centralized identity when appropriate.
- Review audit events and revoke unused API keys.
